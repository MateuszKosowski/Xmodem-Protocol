package org.zespol;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import java.io.IOException;
import java.io.OutputStream;

public class SerialCommunicator implements SerialPortDataListener {

    private Xmodem xmodem = null; // Obiekt, który przetwarza dane

    // Metoda do ustawienia xmodem
    public void setXmodem(Xmodem handler) {
        this.xmodem = handler;
    }

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
            chosenPort.setComPortParameters(9600, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY);
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

    public void sendData(byte[] message) {
        if (chosenPort != null && chosenPort.isOpen()) {
            try {
                OutputStream out = chosenPort.getOutputStream();
                out.write(message);
                out.flush(); // Opcjonalnie, jeśli chcesz wymusić wysłanie
                System.out.println("Wysłano: " + message);
            } catch (IOException e) {
                System.err.println("Błąd podczas wysyłania danych: " + e.getMessage());
            }
        } else {
            System.err.println("Port nie jest otwarty.");
        }
    }

    public void startListening() {
        if (chosenPort != null && chosenPort.isOpen()) {
            // Usuń poprzedniego listenera, jeśli istniał
            chosenPort.removeDataListener();
            // Dodaj siebie jako listenera
            chosenPort.addDataListener(this);
            System.out.println("Rozpoczęto nasłuchiwanie na porcie " + chosenPort.getSystemPortName());
        } else {
            System.out.println("Port nie jest otwarty, nie można rozpocząć nasłuchiwania.");
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
    public void serialEvent(SerialPortEvent event) {
        if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) {
            return;
        }

        // Bajty czekające na odczytanie
        int bytesAvailable = chosenPort.bytesAvailable();
        if (bytesAvailable <= 0) {
            return;
        }

        byte[] readData = new byte[bytesAvailable];
        int numRead = chosenPort.readBytes(readData, readData.length);

        if (numRead > 0) {
            //Przekaż dane do xmodem, on jest odpowiedzialny za ich przetwarzanie
            if (xmodem != null) {
                System.out.println("Listener: Odebrano " + numRead + " bajtów. Przekazuję do xmodem.");
                xmodem.ReceivedDataFromSerial(readData);
            } else {
                System.out.println("Listener: Odebrano dane, ale brak xmodem");
            }
        }
    }
}
