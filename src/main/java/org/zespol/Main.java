package org.zespol;

public class Main {
    public static void main(String[] args) {
        System.out.println("Aplikacja uruchomiona!");

        SerialCommunicator sc = new SerialCommunicator();
        sc.listPorts();
        String port = "COM10";
        sc.open(port);
        sc.startListening();


//        sc.closePort();
//        System.out.println("Aplikacja zakończona.");
    }
}
