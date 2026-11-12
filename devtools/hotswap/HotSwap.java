import com.sun.jdi.Bootstrap;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 把 build/classes 里的 class 通过 JDWP 塞进正在运行的游戏（DCEVM 支持增删方法/字段）。
 *
 * 用法: java HotSwap <port> <class...>
 *       java HotSwap 5005 com.hbm.entity.misc.EntityJindouYun com.hbm.render.HudChunkStats
 */
public class HotSwap {

    private static com.sun.jdi.Value toValue(VirtualMachine vm, String type, String raw) {
        if ("boolean".equals(type)) return vm.mirrorOf(Boolean.parseBoolean(raw));
        if ("byte".equals(type)) return vm.mirrorOf(Byte.parseByte(raw));
        if ("char".equals(type)) return vm.mirrorOf(raw.charAt(0));
        if ("short".equals(type)) return vm.mirrorOf(Short.parseShort(raw));
        if ("int".equals(type)) return vm.mirrorOf(Integer.parseInt(raw));
        if ("long".equals(type)) return vm.mirrorOf(Long.parseLong(raw));
        if ("float".equals(type)) return vm.mirrorOf(Float.parseFloat(raw));
        if ("double".equals(type)) return vm.mirrorOf(Double.parseDouble(raw));
        return vm.mirrorOf(raw);
    }

    public static void main(String[] args) throws Exception {

        int port = 5005;
        List<String> names = new ArrayList<String>();
        List<String> sets = new ArrayList<String>();

        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("--set".equals(a) && i + 1 < args.length) {
                sets.add(args[++i]);
            } else if (a.matches("\\d+")) {
                port = Integer.parseInt(a);
            } else {
                names.add(a);
            }
        }

        String base = System.getProperty("classes", System.getProperty("user.dir") + "/build/classes/java/main/");

        AttachingConnector conn = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if ("com.sun.jdi.SocketAttach".equals(c.name())) conn = c;
        }
        if (conn == null) throw new IllegalStateException("no SocketAttach connector");

        Map<String, Connector.Argument> p = conn.defaultArguments();
        p.get("hostname").setValue("127.0.0.1");
        p.get("port").setValue(String.valueOf(port));

        VirtualMachine vm = conn.attach(p);
        System.out.println("attached: " + vm.name() + " (port " + port + ")");

        int ok = 0, skip = 0, fail = 0;

        for (String n : names) {
            List<ReferenceType> types = vm.classesByName(n);
            if (types.isEmpty()) {
                System.out.println("  skip (not loaded): " + n);
                skip++;
                continue;
            }
            Path f = Paths.get(base + n.replace('.', '/') + ".class");
            if (!Files.exists(f)) {
                System.out.println("  skip (no class file): " + n);
                skip++;
                continue;
            }
            try {
                byte[] bytes = Files.readAllBytes(f);
                vm.redefineClasses(Collections.singletonMap(types.get(0), bytes));
                System.out.println("  ok " + n + " (" + bytes.length + " bytes)");
                ok++;
            } catch (Throwable t) {
                System.out.println("  FAIL " + n + ": " + t);
                fail++;
            }
        }

        int set = 0;
        for (String spec : sets) {
            // Class.field=value
            int eq = spec.indexOf('=');
            int dot = spec.lastIndexOf('.', eq);
            if (eq < 0 || dot < 0) { System.out.println("  bad --set " + spec); continue; }

            String cls = spec.substring(0, dot);
            String field = spec.substring(dot + 1, eq);
            String raw = spec.substring(eq + 1);

            List<ReferenceType> types = vm.classesByName(cls);
            if (types.isEmpty()) { System.out.println("  skip set (not loaded): " + cls); continue; }

            try {
                com.sun.jdi.Field f = types.get(0).fieldByName(field);
                com.sun.jdi.Value v = toValue(vm, f.typeName(), raw);
                ((com.sun.jdi.ClassType) types.get(0)).setValue(f, v);
                System.out.println("  set " + cls + "." + field + " = " + raw);
                set++;
            } catch (Throwable t) {
                System.out.println("  FAIL set " + spec + ": " + t);
            }
        }

        System.out.println("redefined=" + ok + " skipped=" + skip + " failed=" + fail + " fieldsSet=" + set);
        vm.dispose();
    }
}
