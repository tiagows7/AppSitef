package com.appsitef.smartpos.util;

public final class LogSender {

    private LogSender() {
    }

    public static String sendLog(String logFileName) {
        return "Envio de log solicitado: " + logFileName;
    }
}
