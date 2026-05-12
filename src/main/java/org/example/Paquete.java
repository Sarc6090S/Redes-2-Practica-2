package org.example;

import java.nio.ByteBuffer;

public class Paquete {

    public int seqNum;
    public int total;
    public byte[] datos;

    public Paquete(int seqNum, int total, byte[] datos) {
        this.seqNum = seqNum;
        this.total = total;
        this.datos = datos;
    }

    public byte[] serializar() {
        ByteBuffer buffer = ByteBuffer.allocate(8 + datos.length);
        buffer.putInt(seqNum);
        buffer.putInt(total);
        buffer.put(datos);
        return buffer.array();
    }

    public static Paquete deserializar(byte[] bytes, int longitud) {
        ByteBuffer buffer = ByteBuffer.wrap(bytes, 0, longitud);
        int seqNum = buffer.getInt();
        int total = buffer.getInt();
        byte[] datos = new byte[longitud - 8];
        buffer.get(datos);
        return new Paquete(seqNum, total, datos);
    }
}
