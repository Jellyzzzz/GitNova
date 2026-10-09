import com.gitnova.agent.protocol.ExecutionScope;
public final class ScopeProbe {
 public static void main(String[] args) {
  var a=new ExecutionScope("Q","L1",1); a.requireSame(new ExecutionScope("Q","L1",1));
  for(var b: new ExecutionScope[]{new ExecutionScope("X","L1",1),new ExecutionScope("Q","L2",1),new ExecutionScope("Q","L1",2)}) {
   boolean refused=false;try{a.requireSame(b);}catch(IllegalArgumentException e){refused=true;}
   if(!refused)throw new AssertionError("scope mismatch accepted");
  }
  System.out.println("ScopeProbe PASS: Q/L/epoch checks (reference only)");
 }
}
