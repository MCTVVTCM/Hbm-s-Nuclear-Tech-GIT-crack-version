import com.sun.jdi.*;
import com.sun.jdi.connect.*;
import com.sun.jdi.connect.Connector.Argument;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/**
 * 把一批 .class 文件重定义进已经在跑的目标 JVM（需要目标开着 JDWP）。
 *
 * 用法： HotSwapBatch <host> <port> <classRootDir> <classFile> [classFile ...]
 *
 * 关于「游戏里还没加载这个类」：
 *   这种类**不需要、也没法** redefine —— 磁盘上的 .class 就是它第一次被加载时读到的东西，
 *   也就是说改动已经在那儿了，只是还没有任何代码用到它。所以输出叫 PEND 而不是 SKIP：
 *
 *   - 纯懒加载的类（渲染器、工具类、只在某个 GUI 里用到的类）：
 *     第一次用到时自然就是新版本，什么都不用做；
 *   - 只有「init 期注册」的类（新实体 / 方块 / 物品 / 配方 / 渲染注册）才会
 *     「不重启就不生效」——因为注册那一步早就跑完了，注册表里根本没有它。
 *
 *   推送是分批的，游戏在这期间可能刚好把这个类加载起来，所以主推送结束后
 *   还会为 PEND 的类补推几轮（HOT_RETRY_ROUNDS）。
 *
 * 环境变量：
 *   HOT_SWAP_CHUNK     一次 redefineClasses 调用里塞几个类，默认 25。
 *                      每个 JVM 操作都要所有线程停在 safepoint 并做去优化，
 *                      一次调用提交多个类 = 只付一次 safepoint 的代价。
 *   HOT_SWAP_DELAY_MS  两「块」之间的间隔（毫秒），默认 100，让游戏跑几帧。
 *   HOT_RETRY_ROUNDS   主推送后为「还没加载」的类补推几轮，默认 5（0 = 不补推）。
 *   HOT_RETRY_MS       补推轮之间的间隔（毫秒），默认 300。
 *
 * 输出： OK   —— 已替换进游戏
 *       PEND —— 还没被加载：磁盘上已是新版本，首次加载时生效（注册类需重启）
 *       FAIL —— 被 JVM 拒绝（构造器签名 / 继承关系等结构性改动）
 */
public class HotSwapBatch {

    public static void main(String[] args) throws Exception {
        if (args.length < 4) {
            System.out.println("usage: HotSwapBatch <host> <port> <classRootDir> <classFile>...");
            return;
        }
        String host = args[0];
        String port = args[1];
        Path root = Paths.get(args[2]).toAbsolutePath().normalize();
        int chunk = Math.max(1, envInt("HOT_SWAP_CHUNK", 25));
        int delay = envInt("HOT_SWAP_DELAY_MS", 100);
        int retryRounds = Math.max(0, envInt("HOT_RETRY_ROUNDS", 5));
        int retryDelay = Math.max(0, envInt("HOT_RETRY_MS", 300));

        // 1. 先把参数展开成「类名 -> 字节码」，顺便记住序号（输出里的 [3/6]）
        int total = args.length - 3;
        Map<String, Integer> order = new LinkedHashMap<String, Integer>();
        Map<String, byte[]> bytes = new LinkedHashMap<String, byte[]>();
        List<String> unreadable = new ArrayList<String>();

        for (int i = 3; i < args.length; i++) {
            Path p = Paths.get(args[i]).toAbsolutePath().normalize();
            String rel = root.relativize(p).toString();
            String cn = rel.substring(0, rel.length() - ".class".length()).replace('/', '.');
            if (!order.containsKey(cn)) {
                order.put(cn, i - 2);
            }
            try {
                bytes.put(cn, Files.readAllBytes(p));
            } catch (IOException e) {
                unreadable.add(cn);
            }
        }

        VirtualMachine vm = attach(host, port);
        Pusher pusher = new Pusher(vm, order, bytes, total, chunk, delay);

        // 2. 分成「已经加载的」和「还没加载的」
        List<String> ready = new ArrayList<String>();
        List<String> pending = new ArrayList<String>();
        for (String cn : order.keySet()) {
            if (bytes.get(cn) == null) continue;
            if (vm.classesByName(cn).isEmpty()) {
                pending.add(cn);
            } else {
                ready.add(cn);
            }
        }

        // 3. 主推送
        pusher.run(ready, "");

        // 4. 补推：分批推送期间游戏可能刚好把某个 PEND 的类加载起来，
        //    这时候再换一次才算真的换掉。
        for (int round = 1; round <= retryRounds && !pending.isEmpty(); round++) {
            Thread.sleep(retryDelay);
            List<String> woke = new ArrayList<String>();
            for (Iterator<String> it = pending.iterator(); it.hasNext();) {
                String cn = it.next();
                if (!vm.classesByName(cn).isEmpty()) {
                    it.remove();
                    woke.add(cn);
                }
            }
            if (!woke.isEmpty()) {
                System.out.println("[hotswap] 补推第 " + round + " 轮：" + woke.size() + " 个类刚刚被加载，另换一份");
                pusher.run(woke, "   (补推)");
            }
        }

        // 5. 报告
        for (String cn : unreadable) {
            System.out.println("FAIL [" + order.get(cn) + "/" + total + "] " + cn + "   -> 读不到 .class 文件");
            pusher.fail++;
        }
        for (String cn : pending) {
            String hint = looksLikeRegistryEntry(bytes.get(cn))
                    ? "   (还没被加载：磁盘上已是新版本；但这类是 init 期注册才加载的，必须重启客户端)"
                    : "   (还没被加载：不用管，磁盘上已是新版本，它第一次被用到时就是新的)";
            System.out.println("PEND [" + order.get(cn) + "/" + total + "] " + cn + hint);
        }

        System.out.println("[hotswap] 共 " + total + " 个：已替换 " + pusher.ok + "，未加载 " + pending.size()
                + "，失败 " + pusher.fail + "（每批 " + chunk + " 个）");

        vm.dispose();
    }

    /**
     * 粗略判断这个类是不是「注册表里的东西」：字节码里出现这些字符串，
     * 说明它多半只能靠 init 期注册（或首次 spawn）才会被加载。
     * 只用来在 PEND 提示里给一句更有用的话，猜错了也无所谓。
     */
    private static boolean looksLikeRegistryEntry(byte[] b) {
        if (b == null) return false;
        String s = new String(b, java.nio.charset.StandardCharsets.ISO_8859_1);
        return s.contains("registerModEntity") || s.contains("registerEntity")
                || s.contains("GameRegistry") || s.contains("EntityRegistry")
                || s.contains("registerBlock") || s.contains("registerItem");
    }

    private static VirtualMachine attach(String host, String port) throws Exception {
        AttachingConnector conn = null;
        for (AttachingConnector c : Bootstrap.virtualMachineManager().attachingConnectors()) {
            if (c.name().equals("com.sun.jdi.SocketAttach")) conn = c;
        }
        if (conn == null) throw new IllegalStateException("找不到 SocketAttach connector");

        Map<String, Argument> a = conn.defaultArguments();
        a.get("hostname").setValue(host);
        a.get("port").setValue(port);
        return conn.attach(a);
    }

    private static int envInt(String name, int def) {
        String v = System.getenv(name);
        if (v == null || v.trim().isEmpty()) return def;
        try { return Integer.parseInt(v.trim()); } catch (NumberFormatException e) { return def; }
    }

    /** 按块提交：一块一个 JVM 操作（一次 safepoint）。 */
    private static final class Pusher {
        private final VirtualMachine vm;
        private final Map<String, Integer> order;
        private final Map<String, byte[]> bytes;
        private final int total;
        private final int chunk;
        private final int delay;
        int ok;
        int fail;

        Pusher(VirtualMachine vm, Map<String, Integer> order, Map<String, byte[]> bytes,
               int total, int chunk, int delay) {
            this.vm = vm;
            this.order = order;
            this.bytes = bytes;
            this.total = total;
            this.chunk = chunk;
            this.delay = delay;
        }

        void run(List<String> names, String tag) {
            if (names.isEmpty()) return;

            Map<String, ReferenceType> types = new LinkedHashMap<String, ReferenceType>();
            List<String> loaded = new ArrayList<String>();
            for (String cn : names) {
                List<ReferenceType> t = vm.classesByName(cn);
                if (t.isEmpty()) continue;
                loaded.add(cn);
                types.put(cn, t.get(0));
            }

            for (int from = 0; from < loaded.size(); from += chunk) {
                List<String> part = loaded.subList(from, Math.min(from + chunk, loaded.size()));
                Map<ReferenceType, byte[]> map = new LinkedHashMap<ReferenceType, byte[]>();
                for (String cn : part) {
                    map.put(types.get(cn), bytes.get(cn));
                }
                try {
                    vm.redefineClasses(map);
                    for (String cn : part) {
                        System.out.println("OK   [" + order.get(cn) + "/" + total + "] " + cn + tag);
                        ok++;
                    }
                } catch (Exception batchError) {
                    // 这块里有类被拒（比如改了构造器签名），退回逐个，避免一个坏类拖垮整块
                    for (String cn : part) {
                        try {
                            vm.redefineClasses(Collections.singletonMap(types.get(cn), bytes.get(cn)));
                            System.out.println("OK   [" + order.get(cn) + "/" + total + "] " + cn + tag);
                            ok++;
                        } catch (Exception e) {
                            System.out.println("FAIL [" + order.get(cn) + "/" + total + "] " + cn
                                    + "   -> " + e.getMessage());
                            fail++;
                        }
                    }
                }
                if (from + chunk < loaded.size() && delay > 0) {
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                }
            }
        }
    }
}
