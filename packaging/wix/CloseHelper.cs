using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace WirelessBatteryInstaller
{
    internal static class CloseHelper
    {
        private const uint WM_QUERYENDSESSION = 0x0011;
        private const uint WM_ENDSESSION = 0x0016;
        private const int ENDSESSION_CLOSEAPP = 0x00000001;
        private const uint SMTO_ABORTIFHUNG = 0x0002;
        private const uint PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;

        private delegate bool EnumWindowsProc(IntPtr hWnd, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern bool EnumWindows(EnumWindowsProc lpEnumFunc, IntPtr lParam);

        [DllImport("user32.dll")]
        private static extern uint GetWindowThreadProcessId(IntPtr hWnd, out uint lpdwProcessId);

        [DllImport("user32.dll", SetLastError = true, CharSet = CharSet.Auto)]
        private static extern IntPtr SendMessageTimeout(
            IntPtr hWnd,
            uint Msg,
            IntPtr wParam,
            IntPtr lParam,
            uint fuFlags,
            uint uTimeout,
            out IntPtr lpdwResult);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern IntPtr OpenProcess(uint dwDesiredAccess, bool bInheritHandle, int dwProcessId);

        [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
        private static extern bool QueryFullProcessImageName(
            IntPtr hProcess,
            int dwFlags,
            StringBuilder lpExeName,
            ref int lpdwSize);

        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool CloseHandle(IntPtr hObject);

        [STAThread]
        public static int Main(string[] args)
        {
            string targetDirPrefix = ParseNormalizedInstallDir(args);
            string userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
            string configDir = Path.Combine(userProfile, ".wireless-battery-java");
            string lockFile = Path.Combine(configDir, "instance.lock");
            string shutdownSignal = Path.Combine(configDir, "shutdown.signal");
            string showSignal = Path.Combine(configDir, "show.signal");

            bool wroteShutdownSignal = false;
            try
            {
                List<Process> running = FindTargetProcesses(targetDirPrefix);
                if (running.Count == 0)
                {
                    // No instance running from target INSTALLDIR; leave portable/other instances untouched.
                    return 0;
                }

                HashSet<int> targetPids = new HashSet<int>();
                foreach (Process p in running)
                {
                    try { targetPids.Add(p.Id); } catch { }
                }

                // Stage 1: Cooperative shutdown.signal only if instance.lock belongs to one of the target PIDs
                if (DoesLockBelongToTarget(lockFile, targetPids))
                {
                    try
                    {
                        Directory.CreateDirectory(configDir);
                        File.WriteAllText(shutdownSignal, DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(), Encoding.UTF8);
                        wroteShutdownSignal = true;
                    }
                    catch
                    {
                        // Best effort
                    }

                    if (WaitUntilExited(targetDirPrefix, 1200))
                    {
                        return 0;
                    }
                }

                // Stage 2: Clean Win32 EndSession messages strictly to HWNDs owned by target PIDs in INSTALLDIR
                running = FindTargetProcesses(targetDirPrefix);
                HashSet<uint> activeTargetPids = new HashSet<uint>();
                foreach (Process p in running)
                {
                    try { activeTargetPids.Add((uint)p.Id); } catch { }
                }

                List<IntPtr> windows = new List<IntPtr>();
                EnumWindows(delegate (IntPtr hWnd, IntPtr lParam)
                {
                    uint pid;
                    GetWindowThreadProcessId(hWnd, out pid);
                    if (activeTargetPids.Contains(pid))
                    {
                        windows.Add(hWnd);
                    }
                    return true;
                }, IntPtr.Zero);

                foreach (IntPtr hWnd in windows)
                {
                    IntPtr res;
                    SendMessageTimeout(hWnd, WM_QUERYENDSESSION, IntPtr.Zero, (IntPtr)ENDSESSION_CLOSEAPP, SMTO_ABORTIFHUNG, 1500, out res);
                }
                foreach (IntPtr hWnd in windows)
                {
                    IntPtr res;
                    SendMessageTimeout(hWnd, WM_ENDSESSION, (IntPtr)1, (IntPtr)ENDSESSION_CLOSEAPP, SMTO_ABORTIFHUNG, 2500, out res);
                }

                if (WaitUntilExited(targetDirPrefix, 4000))
                {
                    return 0;
                }

                // Stage 3: Last-resort termination strictly for hung processes inside INSTALLDIR
                foreach (Process p in FindTargetProcesses(targetDirPrefix))
                {
                    try
                    {
                        if (!p.HasExited)
                        {
                            p.Kill();
                            p.WaitForExit(2000);
                        }
                    }
                    catch
                    {
                        // Ignore if process exited concurrently
                    }
                }
            }
            catch
            {
                // Never abort MSI sequence on process inspection edge cases
            }
            finally
            {
                if (wroteShutdownSignal)
                {
                    DeleteQuietly(shutdownSignal);
                    DeleteQuietly(showSignal);
                }
            }
            return 0;
        }

        private static string ParseNormalizedInstallDir(string[] args)
        {
            if (args == null)
            {
                return null;
            }
            for (int i = 0; i < args.Length - 1; i++)
            {
                if (string.Equals(args[i], "--dir", StringComparison.OrdinalIgnoreCase))
                {
                    string raw = (args[i + 1] ?? string.Empty).Trim().Trim('"');
                    if (raw.EndsWith("\\.", StringComparison.Ordinal) || raw.EndsWith("/.", StringComparison.Ordinal))
                    {
                        raw = raw.Substring(0, raw.Length - 2);
                    }
                    if (raw.Length == 0)
                    {
                        return null;
                    }
                    try
                    {
                        string full = Path.GetFullPath(raw).TrimEnd('\\', '/');
                        return full + "\\";
                    }
                    catch
                    {
                        return null;
                    }
                }
            }
            return null;
        }

        private static bool DoesLockBelongToTarget(string lockFile, HashSet<int> targetPids)
        {
            try
            {
                if (!File.Exists(lockFile))
                {
                    return true;
                }
                using (FileStream fs = new FileStream(lockFile, FileMode.Open, FileAccess.Read, FileShare.ReadWrite | FileShare.Delete))
                using (StreamReader sr = new StreamReader(fs, Encoding.UTF8))
                {
                    string text = sr.ReadToEnd().Trim();
                    int lockPid;
                    if (int.TryParse(text, out lockPid))
                    {
                        return targetPids.Contains(lockPid);
                    }
                }
            }
            catch
            {
                // If unreadable, fall back to HWND-targeted WM_ENDSESSION only
                return false;
            }
            return false;
        }

        private static bool WaitUntilExited(string targetDirPrefix, int timeoutMs)
        {
            Stopwatch sw = Stopwatch.StartNew();
            while (sw.ElapsedMilliseconds < timeoutMs)
            {
                if (FindTargetProcesses(targetDirPrefix).Count == 0)
                {
                    return true;
                }
                Thread.Sleep(100);
            }
            return FindTargetProcesses(targetDirPrefix).Count == 0;
        }

        private static List<Process> FindTargetProcesses(string targetDirPrefix)
        {
            List<Process> result = new List<Process>();
            int currentPid = Process.GetCurrentProcess().Id;
            int currentSession = Process.GetCurrentProcess().SessionId;
            foreach (Process p in Process.GetProcessesByName("WirelessBattery"))
            {
                try
                {
                    if (p.Id == currentPid || p.SessionId != currentSession || p.HasExited)
                    {
                        continue;
                    }
                    if (!string.IsNullOrEmpty(targetDirPrefix))
                    {
                        string exePath = QueryProcessImagePath(p.Id);
                        if (string.IsNullOrEmpty(exePath)
                            || !exePath.StartsWith(targetDirPrefix, StringComparison.OrdinalIgnoreCase))
                        {
                            continue;
                        }
                    }
                    result.Add(p);
                }
                catch
                {
                    // Ignore inaccessible processes
                }
            }
            return result;
        }

        private static string QueryProcessImagePath(int pid)
        {
            IntPtr hProcess = OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION, false, pid);
            if (hProcess == IntPtr.Zero)
            {
                return null;
            }
            try
            {
                int capacity = 1024;
                StringBuilder sb = new StringBuilder(capacity);
                if (QueryFullProcessImageName(hProcess, 0, sb, ref capacity))
                {
                    try
                    {
                        return Path.GetFullPath(sb.ToString());
                    }
                    catch
                    {
                        return sb.ToString();
                    }
                }
            }
            finally
            {
                CloseHandle(hProcess);
            }
            return null;
        }

        private static void DeleteQuietly(string path)
        {
            try
            {
                if (File.Exists(path))
                {
                    File.Delete(path);
                }
            }
            catch
            {
                // Ignored
            }
        }
    }
}
