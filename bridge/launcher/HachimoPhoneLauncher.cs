// HachimoPhone PC 端启动器（编译成 exe 用，双击即可）
//
// 职责：定位仓库 → 用 node 启动 bridge/start-all.mjs → 把日志留在同一个窗口。
// 查找顺序：HACHIMO_HOME 环境变量 → exe 所在目录（含向上两级）→ 编译时写入的默认路径。
using System;
using System.Diagnostics;
using System.IO;

internal static class HachimoLauncher
{
    private const string DefaultHome = @"F:\vibe_coding\general_app";

    private static string FindHome()
    {
        var env = Environment.GetEnvironmentVariable("HACHIMO_HOME");
        if (!string.IsNullOrEmpty(env)) return Path.GetFullPath(env);

        var exeDir = AppDomain.CurrentDomain.BaseDirectory;
        string[] candidates = { exeDir, Path.Combine(exeDir, @".."), Path.Combine(exeDir, @"..\.."), DefaultHome };
        foreach (var c in candidates)
        {
            var full = Path.GetFullPath(c);
            if (File.Exists(Path.Combine(full, @"bridge\start-all.mjs"))) return full;
        }
        return DefaultHome;
    }

    private static int Main(string[] args)
    {
        Console.Title = "HachimoPhone PC 端";
        Console.OutputEncoding = System.Text.Encoding.UTF8;

        var home = FindHome();
        var script = Path.Combine(home, @"bridge\start-all.mjs");
        if (!File.Exists(script))
        {
            Console.WriteLine("[启动失败] 找不到 " + script);
            Console.WriteLine("请把本程序放回仓库目录，或设置 HACHIMO_HOME 指向仓库根目录。");
            Pause();
            return 1;
        }

        Console.WriteLine("[启动器] 仓库：" + home);
        var psi = new ProcessStartInfo("node", "\"" + script + "\"")
        {
            WorkingDirectory = home,
            UseShellExecute = false,
        };

        try
        {
            using (var child = Process.Start(psi))
            {
                child.WaitForExit();
                return child.ExitCode;
            }
        }
        catch (Exception e)
        {
            Console.WriteLine("[启动失败] " + e.Message);
            Console.WriteLine("请确认已安装 Node.js（>= 18）并加入 PATH。");
            Pause();
            return 1;
        }
    }

    private static void Pause()
    {
        Console.WriteLine();
        Console.WriteLine("按任意键关闭窗口…");
        try { Console.ReadKey(true); } catch { /* 无控制台时忽略 */ }
    }
}
