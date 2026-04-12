package tao.test.flipaccounting;

import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuRemoteProcess;

/**
 * 使用反射强行调用 Shizuku.newProcess
 * 彻底解决编译器报错 "private 访问控制" 的问题
 */
public class ShizukuHelper {
    public static ShizukuRemoteProcess createProcess(String[] cmd, String[] env, String dir) {
        try {
            // 通过反射获取 Shizuku 类
            Class<?> shizukuClass = Class.forName("rikka.shizuku.Shizuku");

            // 找到 newProcess 方法
            Method method = shizukuClass.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);

            // 设置可访问（虽然理论上它就是 public）
            method.setAccessible(true);

            // 执行方法并返回结果
            return (ShizukuRemoteProcess) method.invoke(null, (Object) cmd, (Object) env, (Object) dir);
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}