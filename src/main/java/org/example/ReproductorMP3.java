package org.example;

import javazoom.jl.decoder.*;
import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;

/**
 * Reproductor de audio. Detecta el formato real por magic bytes (no por extensión).
 * Formatos soportados:
 *   MP3             → JLayer (Bitstream/Decoder)
 *   WAV, AIFF, AU   → javax.sound.sampled nativo
 *   OGG Vorbis      → vorbisspi SPI
 *   M4A / AAC       → JAAD (API directa)
 */
public class ReproductorMP3 extends JFrame {

    private enum Formato { MP3, M4A, GENERICO, NO_SOPORTADO }

    private SourceDataLine linea;
    private FloatControl controlVolumen;
    private Thread hiloReproduccion;

    private volatile boolean reproduciendo = false;
    private volatile boolean pausado       = false;
    private volatile boolean detenido      = false;

    private JButton btnPlayPause;
    private JLabel  lblEstado;
    private JSlider sliderVolumen;

    private final String rutaArchivo;

    public ReproductorMP3(String rutaArchivo) {
        this.rutaArchivo = rutaArchivo;
        construirUI();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI
    // ─────────────────────────────────────────────────────────────────────────

    private void construirUI() {
        setTitle("Reproductor — " + new File(rutaArchivo).getName());
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setSize(380, 180);
        setLocationRelativeTo(null);
        setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(new EmptyBorder(14, 16, 14, 16));

        JLabel lblNombre = new JLabel(new File(rutaArchivo).getName(), SwingConstants.CENTER);
        lblNombre.setFont(new Font("SansSerif", Font.BOLD, 13));
        panel.add(lblNombre, BorderLayout.NORTH);

        JPanel panelBotones = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));

        btnPlayPause = new JButton("▶  Reproducir");
        btnPlayPause.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnPlayPause.setPreferredSize(new Dimension(145, 32));
        btnPlayPause.addActionListener(e -> togglePlayPause());

        JButton btnDetener = new JButton("■  Detener");
        btnDetener.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btnDetener.setPreferredSize(new Dimension(115, 32));
        btnDetener.addActionListener(e -> detener());

        panelBotones.add(btnPlayPause);
        panelBotones.add(btnDetener);
        panel.add(panelBotones, BorderLayout.CENTER);

        JPanel panelSur = new JPanel(new BorderLayout(6, 4));

        JPanel panelVol = new JPanel(new BorderLayout(6, 0));
        JLabel lblVolBajo = new JLabel("🔈");
        lblVolBajo.setFont(new Font("SansSerif", Font.PLAIN, 14));
        JLabel lblVolAlto = new JLabel("🔊");
        lblVolAlto.setFont(new Font("SansSerif", Font.PLAIN, 14));
        sliderVolumen = new JSlider(0, 100, 80);
        sliderVolumen.addChangeListener(e -> ajustarVolumen(sliderVolumen.getValue()));
        panelVol.add(lblVolBajo, BorderLayout.WEST);
        panelVol.add(sliderVolumen, BorderLayout.CENTER);
        panelVol.add(lblVolAlto, BorderLayout.EAST);

        lblEstado = new JLabel("Listo para reproducir", SwingConstants.CENTER);
        lblEstado.setFont(new Font("SansSerif", Font.ITALIC, 11));
        lblEstado.setForeground(Color.GRAY);

        panelSur.add(panelVol, BorderLayout.CENTER);
        panelSur.add(lblEstado, BorderLayout.SOUTH);
        panel.add(panelSur, BorderLayout.SOUTH);

        add(panel);
        setVisible(true);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Control
    // ─────────────────────────────────────────────────────────────────────────

    private void togglePlayPause() {
        if (!reproduciendo) {
            iniciarReproduccion();
        } else if (pausado) {
            reanudar();
        } else {
            pausar();
        }
    }

    private void iniciarReproduccion() {
        detenido      = false;
        reproduciendo = true;
        pausado       = false;
        setEstado("▶▶  Reproduciendo...", "⏸  Pausar");

        hiloReproduccion = new Thread(() -> {
            Formato fmt = detectarFormato();

            switch (fmt) {
                case MP3      -> reproducirMP3();
                case M4A      -> reproducirM4A();
                case GENERICO -> reproducirGenerico();
                case NO_SOPORTADO -> {
                    reproduciendo = false;
                    SwingUtilities.invokeLater(() -> btnPlayPause.setText("▶  Reproducir"));
                    return;
                }
            }

            if (!detenido) {
                reproduciendo = false;
                setEstado("Reproducción finalizada", "▶  Reproducir");
            }
        }, "hilo-audio");

        hiloReproduccion.setDaemon(true);
        hiloReproduccion.start();
    }

    private void pausar() {
        pausado = true;
        if (linea != null) linea.stop();
        setEstado("Pausado", "▶  Reanudar");
    }

    private void reanudar() {
        pausado = false;
        if (linea != null) linea.start();
        setEstado("▶▶  Reproduciendo...", "⏸  Pausar");
    }

    private void detener() {
        detenido      = true;
        reproduciendo = false;
        pausado       = false;
        if (linea != null) {
            linea.stop();
            linea.flush();
        }
        if (hiloReproduccion != null) hiloReproduccion.interrupt();
        controlVolumen = null;
        setEstado("Detenido", "▶  Reproducir");
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Detección de formato por magic bytes (no por extensión)
    // ─────────────────────────────────────────────────────────────────────────

    private Formato detectarFormato() {
        try (FileInputStream fis = new FileInputStream(rutaArchivo)) {
            byte[] b = new byte[12];
            int n = fis.read(b);
            if (n < 4) return Formato.NO_SOPORTADO;

            // MP3: cabecera ID3
            if (b[0] == 0x49 && b[1] == 0x44 && b[2] == 0x33) return Formato.MP3;
            // MP3: sync frame directo (0xFF 0xE0–0xFF)
            if ((b[0] & 0xFF) == 0xFF && (b[1] & 0xE0) == 0xE0) return Formato.MP3;

            // WAV: RIFF....WAVE
            if (b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F') return Formato.GENERICO;
            // AIFF
            if (b[0] == 'F' && b[1] == 'O' && b[2] == 'R' && b[3] == 'M') return Formato.GENERICO;
            // OGG Vorbis
            if (b[0] == 'O' && b[1] == 'g' && b[2] == 'g' && b[3] == 'S') return Formato.GENERICO;
            // FLAC
            if (b[0] == 'f' && b[1] == 'L' && b[2] == 'a' && b[3] == 'C') return Formato.GENERICO;

            // MPEG-4 / M4A: bytes 4-7 son 'ftyp'
            if (n >= 8 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') {
                return Formato.M4A;
            }

            // Desconocido: intentar con AudioSystem
            return Formato.GENERICO;

        } catch (IOException e) {
            SwingUtilities.invokeLater(() -> lblEstado.setText("Error leyendo archivo"));
            return Formato.NO_SOPORTADO;
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ruta MP3 — JLayer
    // ─────────────────────────────────────────────────────────────────────────

    private void reproducirMP3() {
        try {
            int sampleRate;
            int canales;
            try (FileInputStream fis = new FileInputStream(rutaArchivo)) {
                Bitstream bs = new Bitstream(fis);
                Header h = bs.readFrame();
                if (h == null) { setEstadoError("MP3 inválido o vacío"); return; }
                sampleRate = h.frequency();
                canales    = (h.mode() == Header.SINGLE_CHANNEL) ? 1 : 2;
                bs.close();
            }

            AudioFormat formato = new AudioFormat(sampleRate, 16, canales, true, false);
            DataLine.Info info  = new DataLine.Info(SourceDataLine.class, formato);
            linea = (SourceDataLine) AudioSystem.getLine(info);
            linea.open(formato);
            iniciarControlVolumen();
            linea.start();

            try (FileInputStream fis = new FileInputStream(rutaArchivo)) {
                Bitstream bitstream = new Bitstream(fis);
                Decoder   decoder   = new Decoder();
                Header    frame;

                while ((frame = bitstream.readFrame()) != null && !detenido) {
                    while (pausado && !detenido) Thread.sleep(30);
                    if (detenido) break;

                    SampleBuffer salida   = (SampleBuffer) decoder.decodeFrame(frame, bitstream);
                    int          longitud = salida.getBufferLength();
                    short[]      muestras = salida.getBuffer();
                    byte[]       pcm      = new byte[longitud * 2];

                    for (int i = 0; i < longitud; i++) {
                        pcm[i * 2]     = (byte)  (muestras[i] & 0xFF);
                        pcm[i * 2 + 1] = (byte) ((muestras[i] >> 8) & 0xFF);
                    }
                    linea.write(pcm, 0, pcm.length);
                    bitstream.closeFrame();
                }
                bitstream.close();
            }

            linea.drain();
            linea.close();

        } catch (BitstreamException | DecoderException e) {
            setEstadoError("Error MP3: " + e.getMessage());
        } catch (LineUnavailableException e) {
            setEstadoError("Línea de audio no disponible");
        } catch (IOException | InterruptedException e) {
            if (!detenido) setEstadoError("Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ruta M4A — JAAD API directa
    // ─────────────────────────────────────────────────────────────────────────

    private void reproducirM4A() {
        try {
            net.sourceforge.jaad.mp4.MP4Container cont =
                    new net.sourceforge.jaad.mp4.MP4Container(
                            new RandomAccessFile(rutaArchivo, "r"));

            net.sourceforge.jaad.mp4.api.Movie movie = cont.getMovie();

            // Buscar la primera pista de audio
            net.sourceforge.jaad.mp4.api.AudioTrack track = null;
            List<net.sourceforge.jaad.mp4.api.Track> tracks = movie.getTracks();
            for (net.sourceforge.jaad.mp4.api.Track t : tracks) {
                if (t instanceof net.sourceforge.jaad.mp4.api.AudioTrack) {
                    track = (net.sourceforge.jaad.mp4.api.AudioTrack) t;
                    break;
                }
            }
            if (track == null) { setEstadoError("M4A sin pista de audio"); return; }

            float sampleRate = (float) track.getSampleRate();
            int   canales    = track.getChannelCount();

            AudioFormat fmt    = new AudioFormat(sampleRate, 16, canales, true, false);
            DataLine.Info info  = new DataLine.Info(SourceDataLine.class, fmt);
            linea = (SourceDataLine) AudioSystem.getLine(info);
            linea.open(fmt);
            iniciarControlVolumen();
            linea.start();

            net.sourceforge.jaad.aac.Decoder decoder =
                    new net.sourceforge.jaad.aac.Decoder(track.getDecoderSpecificInfo());

            net.sourceforge.jaad.mp4.api.Frame frame;
            while ((frame = track.readNextFrame()) != null && !detenido) {
                while (pausado && !detenido) Thread.sleep(30);
                if (detenido) break;

                net.sourceforge.jaad.aac.SampleBuffer buf =
                        new net.sourceforge.jaad.aac.SampleBuffer();
                decoder.decodeFrame(frame.getData(), buf);
                byte[] pcm = buf.getData();
                if (pcm != null && pcm.length > 0) linea.write(pcm, 0, pcm.length);
            }

            linea.drain();
            linea.close();

        } catch (LineUnavailableException e) {
            setEstadoError("Línea de audio no disponible");
        } catch (InterruptedException e) {
            if (!detenido) setEstadoError("Interrumpido");
        } catch (Exception e) {
            if (!detenido) setEstadoError("Error M4A: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ruta genérica — AudioSystem SPI (WAV, AIFF, AU, OGG…)
    // ─────────────────────────────────────────────────────────────────────────

    private void reproducirGenerico() {
        try {
            AudioInputStream streamOriginal = AudioSystem.getAudioInputStream(new File(rutaArchivo));
            AudioFormat fmtOriginal = streamOriginal.getFormat();

            AudioFormat fmtPCM = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    fmtOriginal.getSampleRate(), 16,
                    fmtOriginal.getChannels(),
                    fmtOriginal.getChannels() * 2,
                    fmtOriginal.getSampleRate(), false
            );

            AudioInputStream streamPCM = AudioSystem.getAudioInputStream(fmtPCM, streamOriginal);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmtPCM);
            linea = (SourceDataLine) AudioSystem.getLine(info);
            linea.open(fmtPCM);
            iniciarControlVolumen();
            linea.start();

            byte[] buffer = new byte[4096];
            int leidos;
            while ((leidos = streamPCM.read(buffer, 0, buffer.length)) != -1 && !detenido) {
                while (pausado && !detenido) Thread.sleep(30);
                if (!detenido) linea.write(buffer, 0, leidos);
            }

            linea.drain();
            linea.close();
            streamPCM.close();
            streamOriginal.close();

        } catch (UnsupportedAudioFileException e) {
            setEstadoError("Formato no soportado: " + new File(rutaArchivo).getName());
        } catch (IOException | LineUnavailableException | InterruptedException e) {
            if (!detenido) setEstadoError("Error: " + e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades
    // ─────────────────────────────────────────────────────────────────────────

    private void iniciarControlVolumen() {
        if (linea.isControlSupported(FloatControl.Type.MASTER_GAIN)) {
            controlVolumen = (FloatControl) linea.getControl(FloatControl.Type.MASTER_GAIN);
            ajustarVolumen(sliderVolumen.getValue());
        }
    }

    private void ajustarVolumen(int valor) {
        if (controlVolumen == null) return;
        float min = controlVolumen.getMinimum();
        float max = controlVolumen.getMaximum();
        controlVolumen.setValue(min + (max - min) * (valor / 100.0f));
    }

    private void setEstado(String estado, String textoBoton) {
        SwingUtilities.invokeLater(() -> {
            lblEstado.setForeground(Color.GRAY);
            lblEstado.setText(estado);
            btnPlayPause.setText(textoBoton);
        });
    }

    private void setEstadoError(String msg) {
        reproduciendo = false;
        SwingUtilities.invokeLater(() -> {
            lblEstado.setForeground(Color.RED);
            lblEstado.setText(msg);
            btnPlayPause.setText("▶  Reproducir");
        });
    }

    public static void abrir(String rutaArchivo) {
        SwingUtilities.invokeLater(() -> new ReproductorMP3(rutaArchivo));
    }
}