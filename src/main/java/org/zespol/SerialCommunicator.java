package org.zespol;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class SerialCommunicator implements SerialPortDataListener {
    private SerialPort chosenPort = null;

    public void listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();

        if (ports.length == 0) {
            System.out.println("Nie znaleziono żadnych portów szeregowych.");
            return;
        }

        System.out.println("Dostępne porty szeregowe:");
        for (int i = 0; i < ports.length; ++i) {
            System.out.println((i + 1) + ": " + ports[i].getSystemPortName() + " (" + ports[i].getDescriptivePortName() + ")");
        }
    }

    public void open(String port) {
        chosenPort = SerialPort.getCommPort(port);

        if (chosenPort.openPort()) {
            // Config
            chosenPort.setComPortParameters(9600, 1024, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
            chosenPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 100, 0);

            System.out.println("Port skonfigurowany: 9600 baud, 8 bitów danych, 1 bit stopu, brak parzystości, timeout odczytu 100ms.");
        } else {
            System.out.println("Nie udało się otworzyć portu " + chosenPort.getSystemPortName());
        }
    }

    public void closePort() {
        if (chosenPort != null && chosenPort.isOpen()) {
            if (chosenPort.closePort()) {
                System.out.println("Port " + chosenPort.getSystemPortName() + " zamknięty.");
            } else {
                System.out.println("Nie udało się zamknąć portu " + chosenPort.getSystemPortName());
            }
        }
    }

    public void sendData(String message) {
        if (chosenPort != null && chosenPort.isOpen()) {
            try {
                OutputStream out = chosenPort.getOutputStream();
                out.write(message.getBytes());
                out.flush(); // Opcjonalnie, jeśli chcesz wymusić wysłanie
                System.out.println("Wysłano: " + message);
            } catch (IOException e) {
                System.err.println("Błąd podczas wysyłania danych: " + e.getMessage());
            }
        } else {
            System.err.println("Port nie jest otwarty.");
        }
    }

    public String receiveData() {
        if (chosenPort != null && chosenPort.isOpen()) {
            try {
                InputStream in = chosenPort.getInputStream();
                byte[] readBuffer = new byte[1024]; // Bufo na dane
                int numRead = in.read(readBuffer); // Liczba odczytanych bajtów
                if (numRead > 0) {
                    String received = new String(readBuffer, 0, numRead);
                    System.out.println("Odebrano: " + received);
                    return received;
                } else {
                    System.out.println("Nie odebrano danych w zadanym czasie.");
                }
            } catch (IOException e) {
                System.err.println("Błąd podczas odbierania danych: " + e.getMessage());
            }
        } else {
            System.err.println("Port nie jest otwarty.");
        }
        return null;
    }

    public void startListening() {
        if (chosenPort != null && chosenPort.isOpen()) {
            // Usuń poprzedniego listenera, jeśli istniał
            chosenPort.removeDataListener();
            // Dodaj siebie jako listenera
            chosenPort.addDataListener(this);
            System.out.println("Rozpoczęto nasłuchiwanie na porcie " + chosenPort.getSystemPortName());
        } else {
            System.err.println("Port nie jest otwarty, nie można rozpocząć nasłuchiwania.");
        }
    }

    public void stopListening() {
        if (chosenPort != null && chosenPort.isOpen()) {
            chosenPort.removeDataListener();
            System.out.println("Zakończono nasłuchiwanie na porcie " + chosenPort.getSystemPortName());
        }
    }


    // Metoda wywoływana przez jSerial aby Listener wiedział na jaki typ zdarzeń reagować
    @Override
    public int getListeningEvents() {
        return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
    }

    // jSerialComm automatycznie wywołuje metodę gdy Listener wykryje zdarzenie
    @Override
    public void serialEvent(SerialPortEvent serialPortEvent) {
        if (serialPortEvent.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
            return; // Ignoruj inne typy zdarzeń
        }

        System.out.println("Odebrano dane!");
        receiveData();
    }
}
