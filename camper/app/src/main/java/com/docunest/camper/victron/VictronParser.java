package com.docunest.camper.victron;

import org.json.JSONException;
import org.json.JSONObject;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

/**
 * Decodes Victron "Instant Readout" BLE advertisements (manufacturer id 0x02E1).
 *
 * Layout of the manufacturer data (after the 2-byte company id):
 *   [0]     0x10 product advertisement
 *   [1]     reserved
 *   [2..3]  model id (LE)
 *   [4]     record type (0x01 solar charger, 0x08 AC charger, ...)
 *   [5..6]  nonce / data counter (LE), used as AES-CTR initial counter
 *   [7]     first byte of the encryption key (key check)
 *   [8..]   AES-128-CTR encrypted, bit-packed payload
 */
public final class VictronParser {
    public static final int VICTRON_COMPANY_ID = 0x02E1;

    public static final int RECORD_SOLAR_CHARGER = 0x01;
    public static final int RECORD_BATTERY_MONITOR = 0x02;
    public static final int RECORD_AC_CHARGER = 0x08;

    public static class KeyMismatch extends Exception {}

    public static int recordType(byte[] m) { return m.length > 4 ? m[4] & 0xFF : -1; }
    public static int modelId(byte[] m) { return m.length > 3 ? (m[2] & 0xFF) | ((m[3] & 0xFF) << 8) : -1; }

    /** Decrypts and decodes one advertisement; returns null for unsupported record types. */
    public static JSONObject parse(byte[] m, byte[] key) throws Exception {
        if (m.length < 9 || (m[0] & 0xFF) != 0x10) return null;
        if (m[7] != key[0]) throw new KeyMismatch();
        int nonce = (m[5] & 0xFF) | ((m[6] & 0xFF) << 8);
        byte[] enc = new byte[m.length - 8];
        System.arraycopy(m, 8, enc, 0, enc.length);
        byte[] plain = aesCtr(key, nonce, enc);

        JSONObject o = new JSONObject();
        o.put("model_id", String.format("0x%04X", modelId(m)));
        Bits b = new Bits(plain);
        switch (recordType(m)) {
            case RECORD_SOLAR_CHARGER:
                o.put("type", "solar_charger");
                int state = b.u(8);
                o.put("state", state);
                o.put("state_name", stateName(state));
                o.put("error", b.u(8));
                putScaled(o, "battery_voltage", b.s(16), 0x7FFF, 0.01);
                putScaled(o, "battery_current", b.s(16), 0x7FFF, 0.1);
                putScaled(o, "yield_today_kwh", b.u(16), 0xFFFF, 0.01);
                putScaled(o, "pv_power", b.u(16), 0xFFFF, 1);
                putScaled(o, "load_current", b.u(9), 0x1FF, 0.1);
                break;
            case RECORD_AC_CHARGER:
                o.put("type", "ac_charger");
                int st = b.u(8);
                o.put("state", st);
                o.put("state_name", stateName(st));
                o.put("error", b.u(8));
                putScaled(o, "battery_voltage", b.u(13), 0x1FFF, 0.01);
                putScaled(o, "battery_current", b.u(11), 0x7FF, 0.1);
                putScaled(o, "battery_voltage_2", b.u(13), 0x1FFF, 0.01);
                putScaled(o, "battery_current_2", b.u(11), 0x7FF, 0.1);
                putScaled(o, "battery_voltage_3", b.u(13), 0x1FFF, 0.01);
                putScaled(o, "battery_current_3", b.u(11), 0x7FF, 0.1);
                int t = b.u(7);
                if (t != 0x7F) o.put("temperature", t - 40);
                putScaled(o, "ac_current", b.u(9), 0x1FF, 0.1);
                break;
            case RECORD_BATTERY_MONITOR:
                // SmartShunt / BMV: not owned today, decoded so one can be added later.
                o.put("type", "battery_monitor");
                putScaled(o, "ttg_min", b.u(16), 0xFFFF, 1);
                putScaled(o, "battery_voltage", b.s(16), 0x7FFF, 0.01);
                o.put("alarm", b.u(16));
                b.u(16); // aux input (starter voltage / midpoint / temperature)
                int auxMode = b.u(2);
                o.put("aux_mode", auxMode);
                putScaled(o, "battery_current", b.s(22), 0x1FFFFF, 0.001);
                putScaled(o, "consumed_ah", b.u(20), 0xFFFFF, -0.1);
                putScaled(o, "soc", b.u(10), 0x3FF, 0.1);
                break;
            default:
                return null;
        }
        if (o.has("battery_voltage") && o.has("battery_current")) {
            o.put("battery_power", round(o.getDouble("battery_voltage") * o.getDouble("battery_current"), 1));
        }
        return o;
    }

    private static void putScaled(JSONObject o, String k, int raw, int na, double scale) throws JSONException {
        // Signed fields mark "not available" with the max positive value.
        if (raw == na) return;
        o.put(k, round(raw * scale, 3));
    }

    static double round(double v, int digits) {
        double p = Math.pow(10, digits);
        return Math.round(v * p) / p;
    }

    /** AES-128-CTR with a 128-bit little-endian counter starting at the nonce. */
    static byte[] aesCtr(byte[] key, int nonce, byte[] data) throws Exception {
        Cipher ecb = Cipher.getInstance("AES/ECB/NoPadding");
        ecb.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"));
        byte[] out = new byte[data.length];
        long counter = nonce;
        for (int off = 0; off < data.length; off += 16, counter++) {
            byte[] block = new byte[16];
            for (int i = 0; i < 8; i++) block[i] = (byte) (counter >>> (8 * i));
            byte[] ks = ecb.doFinal(block);
            for (int i = 0; i < 16 && off + i < data.length; i++) out[off + i] = (byte) (data[off + i] ^ ks[i]);
        }
        return out;
    }

    /** Reads little-endian, LSB-first bit fields. Missing bytes read as 1s (= "not available"). */
    static final class Bits {
        private final byte[] d;
        private int pos;
        Bits(byte[] d) { this.d = d; }
        int u(int n) {
            long v = 0;
            for (int i = 0; i < n; i++, pos++) {
                int byteIdx = pos >> 3;
                int bit = byteIdx < d.length ? (d[byteIdx] >> (pos & 7)) & 1 : 1;
                v |= (long) bit << i;
            }
            return (int) v;
        }
        int s(int n) {
            int v = u(n);
            // Keep the all-ones-but-sign "not available" marker as is.
            if (v == (1 << (n - 1)) - 1) return v;
            return (v & (1 << (n - 1))) != 0 ? v - (1 << n) : v;
        }
    }

    public static String stateName(int s) {
        switch (s) {
            case 0: return "Off";
            case 1: return "Low power";
            case 2: return "Fault";
            case 3: return "Bulk";
            case 4: return "Absorption";
            case 5: return "Float";
            case 6: return "Storage";
            case 7: return "Equalize";
            case 9: return "Inverting";
            case 11: return "Power supply";
            case 245: return "Starting up";
            case 246: return "Repeated absorption";
            case 247: return "Recondition";
            case 248: return "BatterySafe";
            case 252: return "External control";
            default: return "State " + s;
        }
    }
}
