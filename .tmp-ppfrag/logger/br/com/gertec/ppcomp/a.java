package br.com.gertec.ppcomp;

import android.app.Activity;
import android.app.DialogFragment;
import android.app.Fragment;
import android.app.FragmentManager;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;

/** Mesma classe do PPComp, gravando o pedido de senha no rastro do app. */
public final class a {
    private String tag;

    static a a(String name) {
        return new a(name);
    }

    private a(String name) {
        this.tag = name;
    }

    final void b(String msg) {
        trace(msg);
        Log.d(tag, msg == null ? "" : msg);
    }

    final void a(String fmt, Object... args) {
        String msg = format(fmt, args);
        trace(msg);
        Log.d(tag, msg);
    }

    private static String format(String fmt, Object[] args) {
        if (fmt == null) {
            return "";
        }
        StringBuffer buf = new StringBuffer(fmt);
        if (args == null) {
            return buf.toString();
        }
        for (Object arg : args) {
            int at = buf.indexOf("{}");
            if (at < 0) {
                break;
            }
            String value = arg == null ? "null" : arg.toString();
            buf.replace(at, at + 2, value);
        }
        return buf.toString();
    }

    public static void d(final Object frag) {
        trace("dsp show begin");
        if (frag == null) {
            trace("dsp show fragment null");
            return;
        }
        try {
            final Activity activity = (Activity) field(frag, "activity").get(frag);
            if (activity == null) {
                trace("dsp activity null");
                return;
            }
            if (Looper.myLooper() == Looper.getMainLooper()) {
                showNumeric(frag, activity);
                waitUntilKeysPlaced(frag);
                rebindKeys(frag);
                trace(visible(frag) ? "dsp show ok" : "dsp show ok sem layout");
                return;
            }
            final boolean[] uiDone = new boolean[] {false};
            final String[] uiError = new String[] {null};
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    try {
                        showNumeric(frag, activity);
                    } catch (Throwable error) {
                        uiError[0] = error.getClass().getName() + ": " + error.getMessage();
                    } finally {
                        uiDone[0] = true;
                    }
                }
            });
            long end = System.currentTimeMillis() + 8000L;
            while (!uiDone[0] && System.currentTimeMillis() < end) {
                Thread.sleep(20L);
            }
            if (uiError[0] != null) {
                trace("dsp show FAIL " + uiError[0]);
                return;
            }
            if (!uiDone[0]) {
                trace("dsp show FAIL ui timeout");
                return;
            }
            end = System.currentTimeMillis() + 4000L;
            while (System.currentTimeMillis() < end) {
                if (visible(frag)) {
                    waitUntilKeysPlaced(frag);
                    rebindKeys(frag);
                    trace("dsp show ok");
                    return;
                }
                Thread.sleep(40L);
            }
            trace("dsp show FAIL ainda invisivel");
        } catch (Throwable error) {
            trace("dsp show FAIL " + error.getClass().getName() + ": " + error.getMessage());
        }
    }

    private static void showNumeric(Object frag, Activity activity) throws Exception {
        boolean numberKeys = field(frag, "showNumberKeys").getBoolean(frag);
        boolean funcKeys = field(frag, "showFuncKeys").getBoolean(frag);
        if (visible(frag) && numberKeys && !funcKeys) {
            trace("dsp ja numerico");
            return;
        }
        FragmentManager fm = activity.getFragmentManager();
        Fragment fragment = (Fragment) frag;
        if (fragment.isAdded()) {
            fm.beginTransaction().remove(fragment).commitNow();
            field(frag, "pinpadCreated").setBoolean(frag, false);
            frag.getClass().getMethod("setPinpadVisible", boolean.class).invoke(null, Boolean.FALSE);
        }
        field(frag, "showNumberKeys").setBoolean(frag, true);
        field(frag, "showFuncKeys").setBoolean(frag, false);
        field(frag, "getKeyResult").setInt(frag, 1);
        ((DialogFragment) frag).show(fm, "Pinpad");
        fm.executePendingTransactions();
        trace("dsp ui shown");
    }

    private static void waitUntilKeysPlaced(Object frag) throws Exception {
        long end = System.currentTimeMillis() + 1500L;
        String place = keyPlace(frag);
        while (System.currentTimeMillis() < end && place.startsWith("0")) {
            Thread.sleep(40L);
            place = keyPlace(frag);
        }
        trace("dsp " + place);
    }

    private static String keyPlace(Object frag) throws Exception {
        Object button = field(frag, "btn1").get(frag);
        if (!(button instanceof android.view.View)) {
            return "0 btn ausente";
        }
        android.view.View view = (android.view.View) button;
        int[] xy = new int[2];
        view.getLocationOnScreen(xy);
        return view.getWidth() + "x" + view.getHeight() + " @" + xy[0] + "," + xy[1];
    }

    private static void rebindKeys(Object frag) {
        try {
            Class<?> gpos = Class.forName("br.com.softwareexpress.sitef.android.PPFragmentGpos700");
            if (!gpos.isInstance(frag)) {
                trace("dsp setKbd skip");
                return;
            }
            trace("dsp " + buttonMap(frag));
            Activity activity = (Activity) field(frag, "activity").get(frag);
            try {
                bindKbdDirect(frag, activity);
            } catch (Throwable error) {
                Throwable cause = error.getCause() == null ? error : error.getCause();
                trace("dsp kbd set FAIL " + cause.getClass().getSimpleName() + " " + cause.getMessage());
            }
            java.lang.reflect.Method setKbd = gpos.getDeclaredMethod("access$000", gpos);
            setKbd.setAccessible(true);
            setKbd.invoke(null, frag);
            Object key = frag.getClass().getMethod("getKey").invoke(frag);
            trace("dsp setKbd ok key=" + key);
            probeCapture(activity);
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            trace("dsp setKbd FAIL " + cause.getClass().getName() + ": " + cause.getMessage());
        }
    }

    private static String buttonMap(Object frag) throws Exception {
        String[] names = {
            "btn0", "btn1", "btn2", "btn3", "btn4", "btn5", "btn6", "btn7", "btn8", "btn9",
            "btnCancel", "btnClear", "btnConfirm"
        };
        StringBuffer buf = new StringBuffer("btns");
        for (String name : names) {
            Object button = field(frag, name).get(frag);
            buf.append(' ').append(name.substring(3)).append(button == null ? "=0" : "=1");
        }
        return buf.toString();
    }

    private static void bindKbdDirect(Object frag, Activity activity) throws Exception {
        Class<?> infoCls = Class.forName("br.com.gertec.gedi.structs.GEDI_KBD_st_Info");
        Object info = infoCls.getConstructor(
                android.widget.Button.class, android.widget.Button.class, android.widget.Button.class,
                android.widget.Button.class, android.widget.Button.class, android.widget.Button.class,
                android.widget.Button.class, android.widget.Button.class, android.widget.Button.class,
                android.widget.Button.class, android.widget.Button.class, android.widget.Button.class,
                android.widget.Button.class, Activity.class
        ).newInstance(
                button(frag, "btn0"), button(frag, "btn1"), button(frag, "btn2"),
                button(frag, "btn3"), button(frag, "btn4"), button(frag, "btn5"),
                button(frag, "btn6"), button(frag, "btn7"), button(frag, "btn8"),
                button(frag, "btn9"), button(frag, "btnCancel"), button(frag, "btnClear"),
                button(frag, "btnConfirm"), activity
        );
        Object gedi = Class.forName("br.com.gertec.gedi.GEDI")
                .getMethod("getInstance", android.content.Context.class)
                .invoke(null, activity.getApplicationContext());
        Object kbd = gedi.getClass().getMethod("getKBD").invoke(gedi);
        kbd.getClass().getMethod("Set", infoCls).invoke(kbd, info);
        trace("dsp kbd set ok");
    }

    private static android.widget.Button button(Object frag, String name) throws Exception {
        return (android.widget.Button) field(frag, name).get(frag);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void probeCapture(Activity activity) {
        try {
            Object gedi = Class.forName("br.com.gertec.gedi.GEDI")
                    .getMethod("getInstance", android.content.Context.class)
                    .invoke(null, activity.getApplicationContext());
            Object kms = gedi.getClass().getMethod("getKMS").invoke(gedi);
            Class<?> ctrlCls = Class.forName("br.com.gertec.gedi.structs.GEDI_KMS_st_Control");
            Object ctrl = ctrlCls.newInstance();
            ctrlCls.getField("bVersion").setByte(ctrl, (byte) 1);
            ctrlCls.getField("uiMinPINLen").setInt(ctrl, 4);
            ctrlCls.getField("uiMaxPINLen").setInt(ctrl, 6);
            ctrlCls.getField("boNullPIN").setBoolean(ctrl, false);
            ctrlCls.getField("boClearSingle").setBoolean(ctrl, true);
            ctrlCls.getField("boAutoEnd").setBoolean(ctrl, false);
            ctrlCls.getField("uiEventTimeout").setInt(ctrl, 2000);
            ctrlCls.getField("uiGlobalTimeout").setInt(ctrl, 2000);
            Class<?> dataCls = Class.forName("br.com.gertec.gedi.structs.GEDI_KMS_st_Data");
            Object data = dataCls.newInstance();
            dataCls.getField("bVersion").setByte(data, (byte) 1);
            dataCls.getField("eOperation").set(data, enumValue("br.com.gertec.gedi.enums.GEDI_KMS_e_OP", "ENCRYPT"));
            dataCls.getField("eMode").set(data, enumValue("br.com.gertec.gedi.enums.GEDI_KMS_e_EncMode", "ECB"));
            Class<?> blkCls = Class.forName("br.com.gertec.gedi.structs.GEDI_KMS_st_PINBlock");
            Object blk = blkCls.newInstance();
            blkCls.getField("bVersion").setByte(blk, (byte) 1);
            blkCls.getField("eBlockType").set(blk, enumValue("br.com.gertec.gedi.enums.GEDI_KMS_e_BLOCKTYPE", "ISO_0"));
            blkCls.getField("szPan").set(blk, "0000000000000000");
            java.util.ArrayList blocks = new java.util.ArrayList();
            blocks.add(blk);
            java.lang.reflect.Method capture = kms.getClass().getMethod(
                    "CapturePINBlock",
                    ctrlCls,
                    boolean.class,
                    dataCls,
                    java.util.List.class
            );
            String[] types = {"DUKPT_TDES", "TDES"};
            int[] indexes = {1, 9, 2, 3};
            for (int i = 0; i < types.length; i++) {
                dataCls.getField("eKeyType").set(data, enumValue("br.com.gertec.gedi.enums.GEDI_KMS_e_KEYTYPE", types[i]));
                dataCls.getField("uiKeyIndex").setInt(data, indexes[i]);
                long started = android.os.SystemClock.elapsedRealtime();
                try {
                    capture.invoke(kms, ctrl, Boolean.FALSE, data, blocks);
                    long spent = android.os.SystemClock.elapsedRealtime() - started;
                    trace("dsp capture " + types[i] + "[" + indexes[i] + "] ok " + spent + "ms");
                    if (spent > 400) break;
                } catch (Throwable error) {
                    long spent = android.os.SystemClock.elapsedRealtime() - started;
                    Throwable cause = error.getCause() == null ? error : error.getCause();
                    String code = "";
                    try {
                        Object value = cause.getClass().getMethod("getErrorCode").invoke(cause);
                        code = " " + value;
                    } catch (Throwable ignored) {
                    }
                    trace("dsp capture " + types[i] + "[" + indexes[i] + "] " + cause.getClass().getSimpleName() + code + " " + cause.getMessage() + " " + spent + "ms");
                    if (spent > 400) break;
                }
            }
            return;
        } catch (Throwable error) {
            Throwable cause = error.getCause() == null ? error : error.getCause();
            String code = "";
            try {
                Object value = cause.getClass().getMethod("getErrorCode").invoke(cause);
                code = " " + value;
            } catch (Throwable ignored) {
            }
            trace("dsp capture " + cause.getClass().getSimpleName() + code + " " + cause.getMessage());
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(String className, String name) throws Exception {
        Class cls = Class.forName(className);
        return Enum.valueOf(cls, name);
    }

    private static boolean visible(Object frag) throws Exception {
        Object value = frag.getClass().getMethod("isPinpadVisible").invoke(frag);
        return Boolean.TRUE.equals(value);
    }

    private static Field field(Object obj, String name) throws NoSuchFieldException {
        Class<?> type = obj.getClass();
        while (type != null) {
            try {
                Field found = type.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void trace(String msg) {
        if (msg == null) {
            return;
        }
        String low = msg.toLowerCase();
        if (!low.contains("pin") && !low.contains("dsp") && !low.contains("kbd")
                && !low.contains("err") && !low.contains("text")) {
            return;
        }
        FileOutputStream out = null;
        try {
            File file = new File("/data/data/com.appsitef.smartpos/files/tef-trace.txt");
            out = new FileOutputStream(file, true);
            String line = System.currentTimeMillis() + " ppcomp " + msg + "\n";
            out.write(line.getBytes("UTF-8"));
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}
