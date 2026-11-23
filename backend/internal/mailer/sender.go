package mailer

import (
	"context"
	"crypto/tls"
	"fmt"
	"log"
	"net"
	"net/mail"
	"net/smtp"
	"os"
	"time"
)

type Sender interface {
	SendVerification(ctx context.Context, email, token string) error
}

// NewFromEnv deliberately requires an explicit mode. 'log' is local-only.
func NewFromEnv() (Sender, error) {
	switch os.Getenv("GITNOVA_MAIL_MODE") {
	case "log":
		return logSender{}, nil
	case "smtp":
		host := os.Getenv("GITNOVA_SMTP_HOST")
		port := os.Getenv("GITNOVA_SMTP_PORT")
		user := os.Getenv("GITNOVA_SMTP_USER")
		password := os.Getenv("GITNOVA_SMTP_PASSWORD")
		from := os.Getenv("GITNOVA_MAIL_FROM")
		addr, err := mail.ParseAddress(from)
		if host == "" || port == "" || user == "" || password == "" || err != nil || addr.Address != from {
			return nil, fmt.Errorf("invalid SMTP configuration")
		}
		return &smtpSender{host: host, port: port, user: user, password: password, from: from}, nil
	default:
		return nil, fmt.Errorf("set GITNOVA_MAIL_MODE=log (local only) or smtp")
	}
}

type logSender struct{}

func (logSender) SendVerification(ctx context.Context, email, token string) error {
	if err := ctx.Err(); err != nil {
		return err
	}
	log.Printf("DEV ONLY email verification for %s: token=%s", email, token)
	return nil
}

type smtpSender struct {
	host, port, user, password, from string
}

// Sends mail over mandatory STARTTLS (typical SMTP submission port 587).
func (s *smtpSender) SendVerification(ctx context.Context, email, token string) error {
	conn, err := (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, "tcp", net.JoinHostPort(s.host, s.port))
	if err != nil {
		return fmt.Errorf("dial SMTP: %w", err)
	}
	defer conn.Close()
	deadline := time.Now().Add(15 * time.Second)
	if ctxDeadline, ok := ctx.Deadline(); ok && ctxDeadline.Before(deadline) {
		deadline = ctxDeadline
	}
	if err := conn.SetDeadline(deadline); err != nil {
		return err
	}
	client, err := smtp.NewClient(conn, s.host)
	if err != nil {
		return fmt.Errorf("SMTP handshake: %w", err)
	}
	defer client.Close()
	if ok, _ := client.Extension("STARTTLS"); !ok {
		return fmt.Errorf("SMTP server does not advertise STARTTLS")
	}
	if err := client.StartTLS(&tls.Config{ServerName: s.host, MinVersion: tls.VersionTLS12}); err != nil {
		return fmt.Errorf("SMTP STARTTLS: %w", err)
	}
	if err := client.Auth(smtp.PlainAuth("", s.user, s.password, s.host)); err != nil {
		return fmt.Errorf("SMTP authentication: %w", err)
	}
	if err := client.Mail(s.from); err != nil {
		return err
	}
	if err := client.Rcpt(email); err != nil {
		return err
	}
	body, err := client.Data()
	if err != nil {
		return err
	}
	message := "From: " + s.from + "\r\n" +
		"To: " + email + "\r\n" +
		"Subject: GitNova email verification\r\n" +
		"MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" +
		"Your GitNova verification token is:\r\n" + token + "\r\n" +
		"This token expires in 15 minutes.\r\n"
	if _, err := body.Write([]byte(message)); err != nil {
		_ = body.Close()
		return err
	}
	if err := body.Close(); err != nil {
		return err
	}
	return client.Quit()
}
