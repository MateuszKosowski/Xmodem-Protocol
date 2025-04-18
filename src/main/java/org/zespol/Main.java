package org.zespol;
import java.util.Scanner;

import java.util.concurrent.TimeUnit;

public class Main {
    public static void main(String[] args) {

        SerialCommunicator sc = new SerialCommunicator();
        Scanner scanner = new Scanner(System.in);
        sc.listPorts();
        String port = "COM10";
        sc.open(port);
        boolean program = true;

        while(program){
            System.out.println("Chcesz odbierać czy wysłać dane?");
            System.out.println("1. Odbieranie");
            System.out.println("2. Wysyłanie");
            System.out.println("3. Wyjście");
            String choice = scanner.nextLine();

            switch(choice){
                case "1":
                    for (int i = 0; i < 6; i++) {

                        // Sprawdzamy, czy już nie odbieramy dancyh
                        if (sc.isTransfer()) {
                            break;
                        }

                        // Wysyłamy NAK i rozpoczynamy nasłuchiwać
                        sc.sendData(new byte[] {(byte) 0x15});
                        sc.startListening();

                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                case "2":
                    System.out.println("W BUDOWIE");
                    //sc.sendData("Hello World");
                    break;

                case "3":
                    program = false;
                    sc.closePort();
                    break;

                default:
                    System.out.println("Zły wybór, podaj 1,2 lub 3!");
                    break;
            }
        }

    }
}
