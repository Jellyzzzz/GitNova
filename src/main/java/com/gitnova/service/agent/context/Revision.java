package com.gitnova.service.agent.context;

public enum Revision {
    TARGET, // push后新HEAD
    BASE, // push前的HEAD
    WORKSPACE // current session Workspace generation
};
