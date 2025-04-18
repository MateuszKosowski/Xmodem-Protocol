package org.zespol;

import java.util.stream.IntStream;

public class Xmodem {
        private static final int SOH = 0x01;    // Start of Header - znak rozpoczynający pakiet danych
        private static final int EOT = 0x04;    // End of Transmission - znak oznaczający koniec transmisji
        private static final int ACK = 0x06;    // Acknowledge - potwierdzenie prawidłowego odbioru pakietu
        private static final int NAK = 0x15;    // Negative Acknowledge - żądanie ponownego przesłania pakietu (błąd)
        private static final int CAN = 0x18;    // Cancel - anulowanie transmisji
        private static final int CRC_CHAR = 0x43; // Znak 'C' - żądanie rozpoczęcia transmisji z kontrolą CRC
        private static final int SUB = 0x1A;    // Substitute - znak wypełniający dla niepełnych bloków danych

        private static byte header;

    public static Byte[] readData(byte[] dataBlock) {

        byte calculatedChecksum = calculateChecksum(dataBlock, 2, 128);
        byte checkSum = dataBlock[131];
        if (calculatedChecksum != checkSum) {
            return null;
        }

        byte numberOfBlock = dataBlock[0];
        byte complement = dataBlock[1];
        byte [] data = new byte[128];
        System.arraycopy(dataBlock, 2, data, 0, 128);

       return IntStream.range(0, data.length).mapToObj(i -> data[i]).toArray(Byte[]::new);
    }

    public static int checkHeader(byte headerP) {
        header = headerP;
        if (headerP == SOH) return 131;
        else if (headerP == CRC_CHAR) return 132;
        else if (headerP == CAN) return -1;
        else return 0;
    }

    /**
     * Oblicza prostą, 8-bitową sumę kontrolną XMODEM.
     * @param data Tablica bajtów danych (tylko 128 bajtów danych payloadu).
     * @param offset Początek danych w tablicy.
     * @param length Długość danych do zsumowania (powinno być DATA_SIZE).
     * @return Obliczona suma kontrolna.
     */
    private static byte calculateChecksum(byte[] data, int offset, int length) {
        byte checksum = 0;
        for (int i = 0; i < length; i++) {
            checksum += data[offset + i]; // Suma modulo 256 jest automatyczna dla typu byte
        }
        return checksum;
    }

    /**
     * Sprawdza poprawność nagłówka bloku (numer bloku i jego dopełnienie).
     * @param blockNumber Otrzymany numer bloku.
     * @param blockNumberComplement Otrzymane dopełnienie numeru bloku.
     * @return true, jeśli nagłówek jest poprawny, false w przeciwnym razie.
     */
    public static boolean verifyBlockNumber(byte blockNumber, byte blockNumberComplement) {
        // W XMODEM, numer_bloku + dopełnienie_numeru_bloku powinno dać 0xFF (255)
        return (byte)(blockNumber + blockNumberComplement) == (byte)0xFF;
    }


}
