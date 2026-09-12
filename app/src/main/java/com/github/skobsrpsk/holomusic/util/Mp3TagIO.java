package com.github.skobsrpsk.holomusic.util;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

/**
 * Простой редактор ID3-тегов для MP3. Никаких сторонних библиотек —
 * минимальный самописный парсер/райтер под конкретные нужды приложения:
 * название, исполнитель, альбом, номер трека, год, жанр.
 *
 * ЧТЕНИЕ поддерживает старые и новые версии тега — ID3v1/v1.1 (хвост
 * файла) и ID3v2.2/2.3/2.4 (заголовок файла), чтобы при открытии
 * редактора не терялись уже существующие значения независимо от того,
 * чем был размечен файл.
 *
 * ЗАПИСЬ, по договорённости, всегда переписывает тег в одном современном
 * формате — ID3v2.3 (плюс обновлённый ID3v1.1-хвост для старых плееров),
 * а не пытается сохранить исходную версию: у v2.2/v2.4 другой формат
 * размеров фреймов, и поддерживать запись во всех вариантах ради
 * "простого" редактора избыточно.
 *
 * Это НЕ полная реализация спецификации ID3 — из вложенных возможностей
 * (обложки, множественные фреймы, редкие encoding-варианты, extended
 * header v2.4 подробно) взято только то, что нужно для базовых текстовых
 * полей. Файлы, которые парсер не смог уверенно прочитать, просто
 * возвращают пустые поля, ничего не ломая.
 */
public class Mp3TagIO {

    private static final String US_ASCII = "US-ASCII";
    private static final String ISO_8859_1 = "ISO-8859-1";

    public static class Tags {
        public String title = "";
        public String artist = "";
        public String album = "";
        public String track = "";
        public String year = "";
        public String genre = "";

        public boolean isEmpty() {
            return title.isEmpty() && artist.isEmpty() && album.isEmpty()
                    && track.isEmpty() && year.isEmpty() && genre.isEmpty();
        }
    }

    // ==================== ЧТЕНИЕ ====================

    public static Tags read(File file) {
        Tags tags = new Tags();
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");

            byte[] header = new byte[10];
            raf.seek(0);
            int readHeader = raf.read(header);
            if (readHeader == 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                int majorVersion = header[3] & 0xFF; // 2, 3 или 4
                int flags = header[5] & 0xFF;
                int tagSize = synchsafeToInt(header, 6); // размер заголовка ВСЕГДА synchsafe, во всех версиях
                if (tagSize > 0 && tagSize <= raf.length() - 10) {
                    byte[] body = new byte[tagSize];
                    raf.readFully(body);
                    int bodyOffset = 0;
                    boolean hasExtendedHeader = (flags & 0x40) != 0 && majorVersion >= 3;
                    if (hasExtendedHeader && body.length >= 4) {
                        // В ID3v2.3 поле "extended header size" НЕ включает
                        // само себя (4 байта) — нужно прибавить их отдельно.
                        // В ID3v2.4 оно synchsafe и уже включает себя.
                        long extSize = (majorVersion >= 4)
                                ? synchsafeToInt(body, 0)
                                : 4L + readUInt32(body, 0);
                        bodyOffset = (int) Math.min(extSize, body.length);
                    }
                    parseId3v2Frames(body, bodyOffset, majorVersion, tags);
                }
            }

            long len = raf.length();
            if (len >= 128) {
                raf.seek(len - 128);
                byte[] v1 = new byte[128];
                raf.readFully(v1);
                if (v1[0] == 'T' && v1[1] == 'A' && v1[2] == 'G') {
                    fillFromId3v1(v1, tags);
                }
            }
        } catch (IOException ignored) {
            // Файл повреждён/недоступен для чтения — вернём то, что успели собрать (может быть пусто).
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                }
            }
        }
        return tags;
    }

    private static void parseId3v2Frames(byte[] body, int offset, int majorVersion, Tags tags) {
        int idLen = (majorVersion == 2) ? 3 : 4;
        int headerLen = (majorVersion == 2) ? 6 : 10; // id+size, либо id+size+flags
        int pos = offset;
        while (pos + headerLen <= body.length) {
            if (body[pos] == 0) break; // дошли до паддинга — дальше нулевые байты

            String frameId;
            try {
                frameId = new String(body, pos, idLen, US_ASCII);
            } catch (UnsupportedEncodingException e) {
                return;
            }

            int frameSize;
            if (majorVersion == 2) {
                frameSize = readUInt24(body, pos + 3);
            } else if (majorVersion == 4) {
                frameSize = synchsafeToInt(body, pos + 4);
            } else {
                frameSize = readUInt32(body, pos + 4);
            }

            int dataStart = pos + headerLen;
            if (frameSize <= 0 || dataStart + frameSize > body.length) break; // подозрительный размер — дальше не читаем

            applyTextFrame(canonicalFrameId(frameId, majorVersion), body, dataStart, frameSize, tags);
            pos = dataStart + frameSize;
        }
    }

    /** Приводит 3-буквенные ID из ID3v2.2 к современным 4-буквенным эквивалентам из v2.3/2.4. */
    private static String canonicalFrameId(String id, int majorVersion) {
        if (majorVersion != 2) return id;
        switch (id) {
            case "TT2": return "TIT2";
            case "TP1": return "TPE1";
            case "TAL": return "TALB";
            case "TRK": return "TRCK";
            case "TYE": return "TYER";
            case "TCO": return "TCON";
            default: return id;
        }
    }

    private static void applyTextFrame(String id, byte[] body, int dataStart, int frameSize, Tags tags) {
        String value = decodeText(body, dataStart, frameSize);
        if (value == null) return;
        value = stripNullsAndTrim(value);
        if (value.isEmpty()) return;

        switch (id) {
            case "TIT2":
                if (tags.title.isEmpty()) tags.title = value;
                break;
            case "TPE1":
                if (tags.artist.isEmpty()) tags.artist = value;
                break;
            case "TALB":
                if (tags.album.isEmpty()) tags.album = value;
                break;
            case "TRCK":
                if (tags.track.isEmpty()) tags.track = firstNumberPart(value);
                break;
            case "TYER":
            case "TDRC":
                if (tags.year.isEmpty()) tags.year = firstYearPart(value);
                break;
            case "TCON":
                if (tags.genre.isEmpty()) tags.genre = resolveGenre(value);
                break;
            default:
                break;
        }
    }

    private static String decodeText(byte[] body, int start, int size) {
        if (size < 1) return null;
        int encoding = body[start] & 0xFF;
        int textStart = start + 1;
        int textLen = size - 1;
        if (textLen <= 0) return "";
        try {
            switch (encoding) {
                case 1:
                    return new String(body, textStart, textLen, "UTF-16"); // с BOM, Java сама определит порядок байт
                case 2:
                    return new String(body, textStart, textLen, "UTF-16BE");
                case 3:
                    return new String(body, textStart, textLen, "UTF-8");
                default:
                    return new String(body, textStart, textLen, ISO_8859_1);
            }
        } catch (UnsupportedEncodingException e) {
            return null;
        }
    }

    private static void fillFromId3v1(byte[] v1, Tags tags) {
        try {
            String title = stripNullsAndTrim(new String(v1, 3, 30, ISO_8859_1));
            String artist = stripNullsAndTrim(new String(v1, 33, 30, ISO_8859_1));
            String album = stripNullsAndTrim(new String(v1, 63, 30, ISO_8859_1));
            String year = stripNullsAndTrim(new String(v1, 93, 4, ISO_8859_1));

            // ID3v1.1: если байт 125 нулевой, а байт 126 — нет, это номер трека.
            int track = -1;
            if (v1[125] == 0 && (v1[126] & 0xFF) != 0) {
                track = v1[126] & 0xFF;
            }
            int genreIndex = v1[127] & 0xFF;

            if (tags.title.isEmpty()) tags.title = title;
            if (tags.artist.isEmpty()) tags.artist = artist;
            if (tags.album.isEmpty()) tags.album = album;
            if (tags.year.isEmpty()) tags.year = year;
            if (tags.track.isEmpty() && track > 0) tags.track = String.valueOf(track);
            if (tags.genre.isEmpty() && genreIndex < ID3V1_GENRES.length) tags.genre = ID3V1_GENRES[genreIndex];
        } catch (UnsupportedEncodingException ignored) {
        }
    }

    // ==================== ЗАПИСЬ ====================

    /**
     * Переписывает тег в файле: свежий ID3v2.3 в начале + обновлённый
     * ID3v1.1-хвост в конце. Реализовано через полное копирование файла
     * во временный (со старым тегом отброшенным и новым на его месте) с
     * последующей заменой оригинала — просто и надёжно, хоть и не самый
     * быстрый вариант при частых правках одного файла (полноценный
     * padding-трюк ради переиспользования места под тег ради простоты
     * решили не делать).
     */
    public static boolean write(File file, Tags tags) {
        RandomAccessFile probe = null;
        File tempFile = null;
        try {
            long oldLen = file.length();
            long oldId3v2Size = 0;

            probe = new RandomAccessFile(file, "r");
            byte[] header = new byte[10];
            if (probe.read(header) == 10 && header[0] == 'I' && header[1] == 'D' && header[2] == '3') {
                oldId3v2Size = 10L + synchsafeToInt(header, 6);
            }

            boolean hasOldId3v1 = false;
            if (oldLen >= 128) {
                probe.seek(oldLen - 128);
                byte[] tail = new byte[3];
                probe.readFully(tail);
                hasOldId3v1 = tail[0] == 'T' && tail[1] == 'A' && tail[2] == 'G';
            }
            probe.close();
            probe = null;

            long audioStart = Math.min(oldId3v2Size, oldLen);
            long audioEnd = hasOldId3v1 ? oldLen - 128 : oldLen;
            if (audioEnd < audioStart) audioEnd = audioStart;

            byte[] newId3v2 = buildId3v2Tag(tags);
            byte[] newId3v1 = buildId3v1Tag(tags);

            tempFile = File.createTempFile("tagedit", ".tmp", file.getParentFile());
            InputStream in = null;
            OutputStream out = null;
            try {
                in = new FileInputStream(file);
                out = new FileOutputStream(tempFile);
                out.write(newId3v2);
                skipFully(in, audioStart);
                copyRange(in, out, audioEnd - audioStart);
                out.write(newId3v1);
            } finally {
                if (in != null) in.close();
                if (out != null) out.close();
            }

            // Раньше здесь сначала удалялся оригинал (file.delete()), а
            // потом делался renameTo() — если rename после этого почему-то
            // не срабатывал (бывает на отдельных точках монтирования),
            // получалось, что оригинал уже удалён, а новый файл остаётся
            // только во временном — и finally ниже его же и подчищал,
            // теряя трек целиком. renameTo() на Android/Linux сам умеет
            // атомарно заменить существующий файл-цель, поэтому удалять
            // заранее не нужно вовсе; а если он всё же откажет — копируем
            // байты поверх оригинала вручную, не трогая его, пока не
            // убедимся, что временный файл записан целиком.
            if (tempFile.renameTo(file)) {
                return true;
            }
            return copyFileContents(tempFile, file);
        } catch (IOException e) {
            return false;
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (IOException ignored) {
                }
            }
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete(); // остался только если rename выше не сработал
            }
        }
    }

    /** Копирует содержимое src поверх dst, не удаляя dst заранее — резервный путь, если renameTo() отказал. */
    private static boolean copyFileContents(File src, File dst) {
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst); // усечёт и перезапишет dst, сам dst как файл не удаляется
            byte[] buf = new byte[8192];
            int read;
            while ((read = in.read(buf)) >= 0) {
                out.write(buf, 0, read);
            }
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            try {
                if (in != null) in.close();
            } catch (IOException ignored) {
            }
            try {
                if (out != null) out.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static byte[] buildId3v2Tag(Tags tags) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        writeTextFrame(frames, "TIT2", tags.title);
        writeTextFrame(frames, "TPE1", tags.artist);
        writeTextFrame(frames, "TALB", tags.album);
        writeTextFrame(frames, "TRCK", tags.track);
        writeTextFrame(frames, "TYER", tags.year);
        writeTextFrame(frames, "TCON", tags.genre);
        byte[] frameBytes = frames.toByteArray();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 3, 0, 0}); // ID3v2.3, revision 0, флагов нет
        out.write(intToSynchsafe(frameBytes.length));
        out.write(frameBytes);
        return out.toByteArray();
    }

    private static void writeTextFrame(ByteArrayOutputStream out, String id, String value) throws IOException {
        if (value == null) return;
        value = value.trim();
        if (value.isEmpty()) return;

        byte[] data;
        if (isLatin1Safe(value)) {
            byte[] textBytes = value.getBytes(ISO_8859_1);
            data = new byte[1 + textBytes.length];
            data[0] = 0; // encoding = ISO-8859-1
            System.arraycopy(textBytes, 0, data, 1, textBytes.length);
        } else {
            // Кириллица и любой другой текст вне Latin-1 — UTF-16 с BOM,
            // иначе он был бы либо потерян, либо превратился в "?????".
            byte[] utf16Bytes = value.getBytes("UTF-16");
            data = new byte[1 + utf16Bytes.length];
            data[0] = 1; // encoding = UTF-16
            System.arraycopy(utf16Bytes, 0, data, 1, utf16Bytes.length);
        }

        out.write(id.getBytes(US_ASCII));
        out.write(intToUInt32(data.length)); // в ID3v2.3 размер фрейма НЕ synchsafe
        out.write(new byte[]{0, 0}); // флаги фрейма
        out.write(data);
    }

    private static byte[] buildId3v1Tag(Tags tags) throws UnsupportedEncodingException {
        byte[] tag = new byte[128];
        Arrays.fill(tag, (byte) 0);
        tag[0] = 'T';
        tag[1] = 'A';
        tag[2] = 'G';
        putLatin1Field(tag, 3, 30, tags.title);
        putLatin1Field(tag, 33, 30, tags.artist);
        putLatin1Field(tag, 63, 30, tags.album);
        putLatin1Field(tag, 93, 4, tags.year);
        // Комментарий (байты 97..126) оставляем пустым, но для ID3v1.1
        // используем последний его байт под номер трека — байт перед ним
        // остаётся нулевым, это и есть признак версии 1.1.
        int track = parseIntSafe(tags.track);
        if (track > 0 && track <= 255) {
            tag[126] = (byte) track;
        }
        int genreIndex = findGenreIndex(tags.genre);
        tag[127] = (byte) (genreIndex >= 0 ? genreIndex : 255); // 255 — общепринятое "жанр не задан"
        return tag;
    }

    private static void putLatin1Field(byte[] tag, int offset, int maxLen, String value) throws UnsupportedEncodingException {
        if (value == null || value.isEmpty()) return;
        // ID3v1 не поддерживает Unicode в принципе — для кириллицы и т.п.
        // символы вне Latin-1 заменяются на '?'. Полноценная версия тега
        // всё равно хранится в ID3v2, который современные плееры читают
        // в приоритете; этот хвост — только для совместимости со старыми.
        StringBuilder safe = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            safe.append(c <= 0xFF ? c : '?');
        }
        byte[] bytes = safe.toString().getBytes(ISO_8859_1);
        int len = Math.min(bytes.length, maxLen);
        System.arraycopy(bytes, 0, tag, offset, len);
    }

    // ==================== Общие утилиты ====================

    private static void skipFully(InputStream in, long count) throws IOException {
        long remaining = count;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) break;
            remaining -= read;
        }
    }

    private static void copyRange(InputStream in, OutputStream out, long count) throws IOException {
        long remaining = count;
        byte[] buf = new byte[8192];
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int read = in.read(buf, 0, toRead);
            if (read < 0) break;
            out.write(buf, 0, read);
            remaining -= read;
        }
    }

    private static boolean isLatin1Safe(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 0xFF) return false;
        }
        return true;
    }

    private static String stripNullsAndTrim(String s) {
        return s.replace("\u0000", "").trim();
    }

    private static String firstNumberPart(String value) {
        // "5/12" -> "5"
        int slash = value.indexOf('/');
        String part = slash >= 0 ? value.substring(0, slash) : value;
        return part.trim();
    }

    private static String firstYearPart(String value) {
        // TDRC в v2.4 может быть полной датой ISO8601 ("2001-05-12") — берём первые 4 цифры.
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < value.length() && digits.length() < 4; i++) {
            char c = value.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
            else if (digits.length() > 0) break;
        }
        return digits.toString();
    }

    private static int parseIntSafe(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** TCON часто хранит жанр как "(17)" или "(17)Rock" — числовая ссылка на список ID3v1. */
    private static String resolveGenre(String value) {
        value = value.trim();
        if (value.startsWith("(")) {
            int close = value.indexOf(')');
            if (close > 1) {
                String numberPart = value.substring(1, close);
                try {
                    int index = Integer.parseInt(numberPart);
                    String rest = value.substring(close + 1).trim();
                    if (!rest.isEmpty()) return rest;
                    if (index >= 0 && index < ID3V1_GENRES.length) return ID3V1_GENRES[index];
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return value;
    }

    private static int findGenreIndex(String genre) {
        if (genre == null || genre.trim().isEmpty()) return -1;
        for (int i = 0; i < ID3V1_GENRES.length; i++) {
            if (ID3V1_GENRES[i].equalsIgnoreCase(genre.trim())) return i;
        }
        return -1;
    }

    private static int synchsafeToInt(byte[] data, int offset) {
        return ((data[offset] & 0x7F) << 21) | ((data[offset + 1] & 0x7F) << 14)
                | ((data[offset + 2] & 0x7F) << 7) | (data[offset + 3] & 0x7F);
    }

    private static byte[] intToSynchsafe(int value) {
        return new byte[]{
                (byte) ((value >> 21) & 0x7F),
                (byte) ((value >> 14) & 0x7F),
                (byte) ((value >> 7) & 0x7F),
                (byte) (value & 0x7F)
        };
    }

    private static int readUInt24(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 16) | ((data[offset + 1] & 0xFF) << 8) | (data[offset + 2] & 0xFF);
    }

    private static int readUInt32(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static byte[] intToUInt32(int value) {
        return new byte[]{
                (byte) ((value >> 24) & 0xFF),
                (byte) ((value >> 16) & 0xFF),
                (byte) ((value >> 8) & 0xFF),
                (byte) (value & 0xFF)
        };
    }

    // Стандартные 80 жанров ID3v1 (список Мичаэля Мутшлера) — этого достаточно
    // для "простого" редактора; расширенный Winamp-список (до 191) не включаем.
    private static final String[] ID3V1_GENRES = {
            "Blues", "Classic Rock", "Country", "Dance", "Disco", "Funk", "Grunge",
            "Hip-Hop", "Jazz", "Metal", "New Age", "Oldies", "Other", "Pop", "R&B",
            "Rap", "Reggae", "Rock", "Techno", "Industrial", "Alternative", "Ska",
            "Death Metal", "Pranks", "Soundtrack", "Euro-Techno", "Ambient",
            "Trip-Hop", "Vocal", "Jazz+Funk", "Fusion", "Trance", "Classical",
            "Instrumental", "Acid", "House", "Game", "Sound Clip", "Gospel",
            "Noise", "AlternRock", "Bass", "Soul", "Punk", "Space", "Meditative",
            "Instrumental Pop", "Instrumental Rock", "Ethnic", "Gothic",
            "Darkwave", "Techno-Industrial", "Electronic", "Pop-Folk",
            "Eurodance", "Dream", "Southern Rock", "Comedy", "Cult", "Gangsta",
            "Top 40", "Christian Rap", "Pop/Funk", "Jungle", "Native US",
            "Cabaret", "New Wave", "Psychedelic", "Rave", "Showtunes", "Trailer",
            "Lo-Fi", "Tribal", "Acid Punk", "Acid Jazz", "Polka", "Retro",
            "Musical", "Rock & Roll", "Hard Rock"
    };
}
