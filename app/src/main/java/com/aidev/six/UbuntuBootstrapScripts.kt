package com.aidev.six

object UbuntuBootstrapScripts {
    fun agentHostScripts(): Map<String, String> = UBUNTU_AGENT_HOST_SCRIPTS

    fun agentPrivotScripts(): Map<String, String> = UBUNTU_AGENT_PRIVOT_SCRIPTS



    /**
     * 将 assets/scripts/ 中的脚本复制到 rootfs 的 /usr/local/bin/。
     * 必须在 Kotlin 层调用（不是 shell 层），因为需要访问 Android AssetManager。
     */
    fun copyAssetScripts(activity: android.app.Activity, rootfs: java.io.File, home: java.io.File) {
        if (!rootfs.isDirectory) return
        // 用户覆盖层目录（可写，优先于出厂脚本，不被本函数覆盖）
        val overridesDir = java.io.File(home, "overrides/bin").apply { mkdirs() }
        // 版本门控：仅当版本标记不符时刷新出厂脚本，避免每次启动重写（保留出厂只读语义）
        val marker = java.io.File(home, ".script-deploy-code")
        val currentCode = runCatching {
            @Suppress("DEPRECATION")
            activity.packageManager.getPackageInfo(activity.packageName, 0).versionCode.toLong()
        }.getOrDefault(0L)
        val needsDeploy = !marker.exists() || marker.readText().trim() != currentCode.toString()
        // 模板目录预置独立于版本门控：幂等补齐，确保已部署设备也能获得 template-wrapper
        ensureTemplateWrapper(activity, rootfs)
        if (!needsDeploy) {
            android.util.Log.d("AIDev", "脚本已是最新 ($currentCode)，跳过复制")
            return
        }
        val binDir = java.io.File(rootfs, "usr/local/bin")
        binDir.mkdirs()
        val scripts = listOf("check-dev-env.sh", "repair-dev-env.sh", "setup-dev-env.sh", "aidev-logcat.sh", "aidev-shizuku.sh", "aidev-apk-info.sh", "aidev-build-request.sh", "aidev-build-log.sh", "aidev-verify-run.sh", "aidev-gen.sh", "aidev-error-why.sh", "aidev-index.sh", "aidev-autoinstall.sh", "android-sh.sh", "aidev-clean.sh", "aidev-backup.sh", "aidev-anr.sh", "aidev-tombstone.sh", "aidev-crash-why.sh", "aidev-dumpsys.sh", "create-compose-project.sh", "aidev-precache.sh", "aidev-repo.sh", "aidev-bridge.sh", "aidev-notify.sh")
        for (script in scripts) {
            val dstName = script.removeSuffix(".sh")
            val dst = java.io.File(binDir, dstName)
            try {
                // 先清除只读位，否则上一次 setReadOnly 会导致 outputStream 打开失败、写入被吞（脚本永不更新）
                runCatching { dst.setWritable(true) }
                activity.assets.open("scripts/$script").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                dst.setExecutable(true)
                // 出厂脚本标注只读：约定不可在终端内篡改；root(-0) 下非硬挡，
                // 防篡改由「用户覆盖层优先 + 版本升级恢复」共同保障（见 docs/error-journal 2026-07-17）。
                runCatching { dst.setReadOnly() }
                android.util.Log.d("AIDev", "已复制脚本: $dstName -> ${dst.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w("AIDev", "无法复制脚本 $script: ${e.message}")
            }
        }
        // 系统控制脚本（通知、截图、音量、亮度、剪贴板、代理），与 TerminalShellAssets.kt 的 writeSystemScript 保持同步。
        // 部署到 rootfs /usr/local/bin/ 使 PRoot 内也可用（如 aidev-build-request 需 sysnotify 发通知）。
        for (sysName in listOf("sysnotify", "screencap", "volume", "brightness", "sysclip", "aidev-proxy")) {
            val dst = java.io.File(binDir, sysName)
            try {
                runCatching { dst.setWritable(true) }
                dst.writeText(systemScriptContent(sysName) + "\n")
                dst.setExecutable(true)
                runCatching { dst.setReadOnly() }
                android.util.Log.d("AIDev", "已复制系统脚本: $sysName -> ${dst.absolutePath}")
            } catch (e: Exception) {
                android.util.Log.w("AIDev", "无法复制系统脚本 $sysName: ${e.message}")
            }
        }
        // 公共 lib 目录：被 aidev-build-request / aidev-notify / aidev-anr / aidev-tombstone 等 source，
        // 必须与脚本同目录（/usr/local/bin/lib），否则这些命令会在 set -e 下崩溃（见脚本审计）。
        val libDir = java.io.File(binDir, "lib").apply { mkdirs() }
        runCatching {
            val libEntries = activity.assets.list("scripts/lib") ?: emptyArray()
            for (entry in libEntries) {
                val dst = java.io.File(libDir, entry)
                activity.assets.open("scripts/lib/$entry").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                runCatching { dst.setReadOnly() }
                android.util.Log.d("AIDev", "已复制 lib: $entry -> ${dst.absolutePath}")
            }
        }.onFailure { e -> android.util.Log.w("AIDev", "无法复制 lib 目录: ${e.message}") }
        runCatching { marker.writeText("$currentCode\n") }
    }

    fun aidevUbuntuCommandScript(homePath: String, nativeDir: String, prootExtraLibsPath: String): String =
        """
        #!/system/bin/sh
        set -u

        cmd="${'$'}{1:-ubuntu}"
        shift 2>/dev/null || true

        AIDEV_HOME="${'$'}{AIDEV_HOME:-$homePath}"
        AIDEV_BIN="${'$'}{AIDEV_BIN:-${'$'}AIDEV_HOME/dev-env/bin}"
        AIDEV_OVERRIDES="${'$'}{AIDEV_OVERRIDES:-${'$'}AIDEV_HOME/overrides/bin}"
        AIDEV_ROOTFS="${'$'}{AIDEV_ROOTFS:-${'$'}AIDEV_HOME/ubuntu-rootfs}"
        AIDEV_WORKSPACE="${'$'}{AIDEV_WORKSPACE:-${'$'}AIDEV_HOME/workspace}"
        AIDEV_NATIVE="${'$'}{AIDEV_NATIVE:-$nativeDir}"
        # proot 可执行体在 nativeLibraryDir（唯一 exec 允许区；filesDir/cacheDir/code_cache 均被 W^X 拒绝）
        AIDEV_PROOT_LIBS="${'$'}{AIDEV_PROOT_LIBS:-$nativeDir}"
        # libtalloc.so.2 版本化 soname 符号链接目录
        AIDEV_PROOT_EXTRA_LIBS="${'$'}{AIDEV_PROOT_EXTRA_LIBS:-$prootExtraLibsPath}"
        AIDEV_PROOT="${'$'}{AIDEV_PROOT:-${'$'}AIDEV_PROOT_LIBS/libproot.so}"
        AIDEV_PROOT_LOADER="${'$'}{AIDEV_PROOT_LOADER:-${'$'}AIDEV_PROOT_LIBS/libproot_loader.so}"
        PROOT_LOADER="${'$'}AIDEV_PROOT_LOADER"
        # proot 及其依赖 libtalloc.so.2 / libandroid-shmem.so 的动态链接搜索路径
        LD_LIBRARY_PATH="${'$'}AIDEV_PROOT_EXTRA_LIBS:${'$'}AIDEV_PROOT_LIBS${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        PROOT_TMP_DIR="${'$'}{PROOT_TMP_DIR:-${'$'}AIDEV_HOME/proot-tmp}"
        export AIDEV_HOME AIDEV_BIN AIDEV_ROOTFS AIDEV_WORKSPACE AIDEV_NATIVE AIDEV_PROOT_LIBS AIDEV_PROOT_EXTRA_LIBS AIDEV_PROOT AIDEV_PROOT_LOADER PROOT_LOADER PROOT_TMP_DIR LD_LIBRARY_PATH

        # 是否已在 rootfs 内。此时宿主 home 绑定为 /host-home 或 AIDEV_REALM 为 U；
        # 不能再嵌套 proot，命令应就地执行。
        if [ -d /host-home ] || [ "${'$'}{AIDEV_REALM:-H}" = "U" ]; then
          AIDEV_IN_ROOTFS=1
        else
          AIDEV_IN_ROOTFS=0
        fi

        ubuntu_url="${'$'}{AIDEV_UBUNTU_URL:-https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/24.04.4/release/ubuntu-base-24.04.4-base-arm64.tar.gz}"

        has_ubuntu() {
          [ -f "${'$'}AIDEV_ROOTFS/.aidev-rootfs-ready" ] &&
          [ -f "${'$'}AIDEV_ROOTFS/etc/os-release" ] &&
          { [ -x "${'$'}AIDEV_ROOTFS/bin/sh" ] || [ -x "${'$'}AIDEV_ROOTFS/bin/bash" ]; }
        }

        link_or_symlink() {
          left="${'$'}1"
          right="${'$'}2"
          if [ -e "${'$'}AIDEV_ROOTFS.tmp/${'$'}left" ] && [ ! -e "${'$'}AIDEV_ROOTFS.tmp/${'$'}right" ]; then
            ( cd "${'$'}AIDEV_ROOTFS.tmp/${'$'}(dirname "${'$'}right")" && ln -sf "${'$'}(basename "${'$'}left")" "${'$'}(basename "${'$'}right")" ) 2>/dev/null || true
          fi
          if [ -e "${'$'}AIDEV_ROOTFS.tmp/${'$'}right" ] && [ ! -e "${'$'}AIDEV_ROOTFS.tmp/${'$'}left" ]; then
            ( cd "${'$'}AIDEV_ROOTFS.tmp/${'$'}(dirname "${'$'}left")" && ln -sf "${'$'}(basename "${'$'}right")" "${'$'}(basename "${'$'}left")" ) 2>/dev/null || true
          fi
        }

        download_file() {
          url="${'$'}1"; out="${'$'}2"; part="${'$'}out.part"
          mkdir -p "${'$'}(dirname "${'$'}out")"
          echo "下载地址：${'$'}url"
          if command -v curl >/dev/null 2>&1; then
            curl -fsSL --retry 3 -C - -o "${'$'}part" "${'$'}url" || return ${'$'}?
          elif command -v wget >/dev/null 2>&1; then
            wget -q -c -O "${'$'}part" "${'$'}url" || return ${'$'}?
          elif /system/bin/toybox wget --help >/dev/null 2>&1; then
            /system/bin/toybox wget -O "${'$'}part" "${'$'}url" || return ${'$'}?
          else
            echo "没有可用下载器：curl/wget/toybox wget 都不可用。"
            echo "下一步将改为 APK assets 内置 rootfs 或 Kotlin 下载器，避免依赖系统命令。"
            return 1
          fi
          [ -s "${'$'}part" ] && mv "${'$'}part" "${'$'}out"
        }

        install_ubuntu() {
          case "${'$'}{1:-}" in
            --clean)
              rm -rf "${'$'}AIDEV_ROOTFS" "${'$'}AIDEV_ROOTFS.tmp" "${'$'}AIDEV_HOME/dev-env/tmp/ubuntu-base.tar.gz" "${'$'}AIDEV_HOME/dev-env/tmp/ubuntu-base.tar.gz.part"
              echo "已清理 Ubuntu rootfs 与下载缓存。"
              return 0
              ;;
          esac

          if has_ubuntu; then
            echo "Ubuntu 已就绪：${'$'}AIDEV_ROOTFS"
            return 0
          fi

          abi="${'$'}(getprop ro.product.cpu.abi 2>/dev/null || echo arm64-v8a)"
          case "${'$'}abi" in
            arm64-v8a|aarch64) ;;
            *) echo "当前自动 Ubuntu 仅支持 arm64，设备 ABI=${'$'}abi"; return 1 ;;
          esac

          [ -x "${'$'}AIDEV_PROOT" ] || { echo "proot 不存在：${'$'}AIDEV_PROOT"; return 1; }

          mkdir -p "${'$'}AIDEV_HOME/dev-env/tmp" "${'$'}PROOT_TMP_DIR"
          tar_file="${'$'}AIDEV_HOME/dev-env/tmp/ubuntu-base.tar.gz"
          if [ ! -s "${'$'}tar_file" ]; then
            echo "[1/4] 自动下载 Ubuntu Base 24.04 arm64..."
            download_file "${'$'}ubuntu_url" "${'$'}tar_file" || return ${'$'}?
          else
            echo "[1/4] 使用已下载缓存：${'$'}tar_file"
          fi

          echo "[2/4] 准备 rootfs 目录..."
          rm -rf "${'$'}AIDEV_ROOTFS.tmp" "${'$'}AIDEV_ROOTFS"
          mkdir -p "${'$'}AIDEV_ROOTFS.tmp"

          echo "[3/4] 解包 Ubuntu rootfs..."
          tar_log="${'$'}AIDEV_HOME/dev-env/tmp/tar-install.log"
          if command -v tar >/dev/null 2>&1; then
            ( cd "${'$'}AIDEV_ROOTFS.tmp" && tar --no-same-owner --no-same-permissions -xzf "${'$'}tar_file" ) 2>"${'$'}tar_log"
            tar_rc="${'$'}?"
          else
            echo "系统缺少 tar，无法解包 rootfs。"
            return 1
          fi

          link_or_symlink "usr/bin/perl" "usr/bin/perl5.38.2"
          link_or_symlink "usr/bin/gunzip" "usr/bin/uncompress"

          if [ "${'$'}tar_rc" -ne 0 ]; then
            if [ -f "${'$'}AIDEV_ROOTFS.tmp/etc/os-release" ] && { [ -x "${'$'}AIDEV_ROOTFS.tmp/bin/sh" ] || [ -x "${'$'}AIDEV_ROOTFS.tmp/bin/bash" ]; }; then
              echo "检测到 Android hardlink 限制，已自动转为 symlink 继续。"
            else
              echo "解包失败，请检查 tar 能力、存储空间或下载完整性。"
              [ -s "${'$'}tar_log" ] && tail -20 "${'$'}tar_log"
              rm -rf "${'$'}AIDEV_ROOTFS.tmp"
              return 1
            fi
          fi

           echo "[4/4] 初始化 apt 源、DNS 和完成标记..."
           mkdir -p "${'$'}AIDEV_ROOTFS.tmp/etc/apt" "${'$'}AIDEV_ROOTFS.tmp/root/projects"
           mkdir -p "${'$'}AIDEV_WORKSPACE"
          cat > "${'$'}AIDEV_ROOTFS.tmp/etc/apt/sources.list" <<'AIDEV_APT_EOF'
deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble main universe multiverse restricted
deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-updates main universe multiverse restricted
deb http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports/ noble-security main universe multiverse restricted
AIDEV_APT_EOF
          echo "nameserver 223.5.5.5" > "${'$'}AIDEV_ROOTFS.tmp/etc/resolv.conf"
          ensure_android_groups "${'$'}AIDEV_ROOTFS.tmp"
          date '+%F %T' > "${'$'}AIDEV_ROOTFS.tmp/.aidev-rootfs-ready"
          mv "${'$'}AIDEV_ROOTFS.tmp" "${'$'}AIDEV_ROOTFS"
          echo "Ubuntu 初始化完成。"
        }

        ensure_android_groups() {
          target_root="${'$'}{1:-${'$'}AIDEV_ROOTFS}"
          group_file="${'$'}target_root/etc/group"
          [ -f "${'$'}group_file" ] || return 0
          for gid in ${'$'}(id -G 2>/dev/null); do
            case "${'$'}gid" in
              ''|*[!0-9]*) continue ;;
            esac
            # 纯 shell 检查，不依赖 grep：宿主 /system/bin/grep 可能为坏架构二进制
            # （cannot execute: required file not found），避免 enter_ubuntu 时打印报错。
            _found=0
            while IFS=: read -r _g _p _gid _rest; do
              [ "${'$'}_gid" = "${'$'}gid" ] && { _found=1; break; }
            done < "${'$'}group_file"
            [ "${'$'}_found" = 1 ] && continue
            echo "android_gid_${'$'}gid:x:${'$'}gid:" >> "${'$'}group_file"
          done
        }

        ubuntu_logo() {
          status="${'$'}1"
          printf '%s\n' \
            ' AAA   IIIII  DDDD   EEEEE  V   V' \
            'A   A    I    D   D  E      V   V' \
            'A   A    I    D   D  EEE    V   V' \
            'AAAAA    I    D   D  E       V V ' \
            'A   A  IIIII  DDDD   EEEEE    V  ' \
            "  ${'$'}status"
        }

        aidev_doctor_android() {
          echo "== AIDev Doctor =="
          echo "mode: Android shell"
          echo "version: ${'$'}{AIDEV_VERSION:-unknown}"
          echo "time: ${'$'}(date '+%F %T' 2>/dev/null || echo unknown)"
          echo
          [ -d "${'$'}AIDEV_HOME" ] && echo "[OK] AIDEV_HOME: ${'$'}AIDEV_HOME" || echo "[FAIL] AIDEV_HOME missing: ${'$'}AIDEV_HOME"
          [ -f "${'$'}AIDEV_ROOTFS/.aidev-rootfs-ready" ] && echo "[OK] Ubuntu ready marker" || echo "[WARN] Ubuntu ready marker missing"
          [ -f "${'$'}AIDEV_ROOTFS/etc/os-release" ] && echo "[OK] Ubuntu os-release" || echo "[WARN] Ubuntu os-release missing"
          [ -x "${'$'}AIDEV_PROOT" ] && echo "[OK] PRoot: ${'$'}AIDEV_PROOT" || echo "[FAIL] PRoot missing: ${'$'}AIDEV_PROOT"
          [ -f "${'$'}AIDEV_PROOT_LOADER" ] && echo "[OK] PRoot loader" || echo "[WARN] PRoot loader missing"
          [ -f "${'$'}AIDEV_ROOTFS/etc/resolv.conf" ] && echo "[OK] DNS config" || echo "[WARN] DNS config missing"
          [ -f "${'$'}AIDEV_ROOTFS/etc/apt/sources.list" ] && echo "[OK] apt sources" || echo "[WARN] apt sources missing"
          command -v tar >/dev/null 2>&1 && echo "[OK] Android tar available" || echo "[WARN] Android tar missing"
          command -v curl >/dev/null 2>&1 && echo "[OK] curl available" || echo "[INFO] curl unavailable"
          command -v wget >/dev/null 2>&1 && echo "[OK] wget available" || echo "[INFO] wget unavailable"
          echo "groups: ${'$'}(id -G 2>/dev/null || echo unknown)"
          echo "disk:"
          df -h "${'$'}AIDEV_HOME" 2>/dev/null | tail -1 || true
          echo
          echo "如果已经在 Ubuntu 内，请直接运行：aidev-doctor"
        }

        ensure_ubuntu_helpers() {
          has_ubuntu || return 0
          mkdir -p "${'$'}AIDEV_ROOTFS/usr/local/bin"
          cat > "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-doctor" <<'AIDEV_DOCTOR_EOF'
#!/bin/sh
echo "== AIDev Doctor =="
echo "mode: Ubuntu PRoot"
echo "version: ${'$'}{AIDEV_VERSION:-unknown}"
echo "time: $(date '+%F %T' 2>/dev/null || echo unknown)"
echo
if [ -f /etc/os-release ]; then
  . /etc/os-release
  echo "[OK] Ubuntu: ${'$'}{PRETTY_NAME:-unknown}"
else
  echo "[FAIL] /etc/os-release missing"
fi
[ -f /.aidev-rootfs-ready ] && echo "[OK] rootfs ready marker" || echo "[WARN] rootfs ready marker missing"
[ -d /host-home ] && echo "[OK] host home mounted: /host-home" || echo "[WARN] /host-home missing"
[ -f /host-home/dev-env/bin/aidev-ubuntu-core ] && echo "[OK] host command core" || echo "[WARN] host command core missing"
[ -r /etc/resolv.conf ] && echo "[OK] DNS config" || echo "[WARN] DNS config missing"
[ -r /etc/apt/sources.list ] && echo "[OK] apt sources" || echo "[WARN] apt sources missing"
command -v apt-get >/dev/null 2>&1 && echo "[OK] apt-get available" || echo "[WARN] apt-get missing"
command -v bash >/dev/null 2>&1 && echo "[OK] bash available" || echo "[WARN] bash missing"
echo "user: $(id 2>/dev/null || echo unknown)"
echo "pwd: $(pwd)"
echo "disk:"
df -h / 2>/dev/null | tail -1 || true
AIDEV_DOCTOR_EOF
          chmod 755 "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-doctor" 2>/dev/null || true
          cat > "${'$'}AIDEV_ROOTFS/usr/local/bin/ubuntu" <<'AIDEV_UBUNTU_EOF'
#!/bin/sh
echo "已经在 AIDev Ubuntu 环境中。"
echo "当前目录：$(pwd)"
echo "诊断命令：aidev-doctor"
AIDEV_UBUNTU_EOF
          chmod 755 "${'$'}AIDEV_ROOTFS/usr/local/bin/ubuntu" 2>/dev/null || true
          cat > "${'$'}AIDEV_ROOTFS/usr/local/bin/install-ubuntu" <<'AIDEV_INSTALL_EOF'
#!/bin/sh
echo "当前已经在 Ubuntu 内。"
echo "如需清理或重装 rootfs，请先退出 Ubuntu，再在 AIDev Android shell 中运行：install-ubuntu --clean"
AIDEV_INSTALL_EOF
          chmod 755 "${'$'}AIDEV_ROOTFS/usr/local/bin/install-ubuntu" 2>/dev/null || true
          cat > "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-auto-bootstrap" <<'AIDEV_BOOTSTRAP_EOF'
#!/bin/sh
ubuntu "$@"
AIDEV_BOOTSTRAP_EOF
          chmod 755 "${'$'}AIDEV_ROOTFS/usr/local/bin/aidev-auto-bootstrap" 2>/dev/null || true

          # 将 dev-env/bin 中的两端共用脚本复制到 rootfs（单来源 → 双端可用）
          for script in aidev-current-project list-listen-ports task-list task-run; do
            if [ -f "${'$'}AIDEV_BIN/.privot/${'$'}script" ]; then
              cp "${'$'}AIDEV_BIN/.privot/${'$'}script" "${'$'}AIDEV_ROOTFS/usr/local/bin/${'$'}script"
              chmod 755 "${'$'}AIDEV_ROOTFS/usr/local/bin/${'$'}script" 2>/dev/null || true
            fi
          done

          mkdir -p "${'$'}AIDEV_ROOTFS/root"
          cat > "${'$'}AIDEV_ROOTFS/root/.bashrc" <<'AIDEV_BASHRC_AGENT_EOF'
# AIDEV_PWD_HOOK_BEGIN
. /host-home/.aidevrc
# Replace /system/bin/sh -> /bin/sh in .aidevrc function bodies (pure bash, no sed)
_p=/system/bin/sh _q=/bin/sh
eval "${'$'}(
              declare -f |
              while IFS= read -r __l; do
                case "${'$'}__l" in
                  *${'$'}_p*) printf '%s\n' "${'$'}{__l//${'$'}_p/${'$'}_q}" ;;
                  *) printf '%s\n' "${'$'}__l" ;;
                esac
              done
)"
unset _p _q
_p="${'$'}PATH"; PATH=""
while [ -n "${'$'}_p" ]; do _e="${'$'}{_p%%:*}"; case "${'$'}_e" in /system/*) ;; *) PATH="${'$'}{PATH:+${'$'}PATH:}${'$'}_e" ;; esac; [ "${'$'}_p" = "${'$'}_e" ] && _p="" || _p="${'$'}{_p#*:}"; done
unset _p _e
aidev_write_pwd() {
  pwd > /host-home/.aidev-current-pwd 2>/dev/null || true
}
case "${'$'}{PROMPT_COMMAND:-}" in
  *aidev_write_pwd*) ;;
  *) PROMPT_COMMAND="aidev_write_pwd${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;;
esac
aidev_write_pwd
# AIDEV_PWD_HOOK_END

# JDK 兜底：PRoot 启动 env 已注入真实 JDK 到 PATH，但若会话异常缺失，则探测预置 JDK 补回，
# 避免本地 lint / aidev-apk-info / 签名等依赖 java 的能力不可用。
if ! command -v java >/dev/null 2>&1; then
  for _c in /opt/jdk-17* /opt/jdk-* /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/*; do
    if [ -x "${'$'}{_c}/bin/java" ]; then
      export JAVA_HOME="${'$'}_c"
      export PATH="${'$'}_c/bin:${'$'}PATH"
      break
    fi
  done
  unset _c
fi
AIDEV_BASHRC_AGENT_EOF
        }

        # 定位真实 JDK：宿主预置在 /opt/jdk-17* ，兜底扫 /usr/lib/jvm；绝不写死悬空路径
        aidev_resolve_jdk() {
          _jdk=""
          for _c in /opt/jdk-17* /opt/jdk-* /usr/lib/jvm/java-17-openjdk-arm64 /usr/lib/jvm/*; do
            [ -x "${'$'}{_c}/bin/java" ] && { _jdk="${'$'}_c"; break; }
          done
          echo "${'$'}_jdk"
        }

        # 公共 proot 绑定参数（enter_ubuntu 和 run_ubuntu_command 共用）
        proot_common_binds() {
          _binds="-b /dev -b /proc -b /sys -b /system/lib64 -b /system/lib -b /system/bin -b /system/etc -b /system/framework -b /sdcard -b /storage -b ${'$'}AIDEV_HOME:/host-home -b ${'$'}AIDEV_WORKSPACE:/workspace"
          # 仅当宿主路径存在时才绑定，避免 proot 启动时打印 "can't sanitize binding" 噪音警告
          [ -e /root/.gradle ] && _binds="${'$'}_binds -b /root/.gradle"
          _JDK=${'$'}(aidev_resolve_jdk)
          [ -n "${'$'}_JDK" ] && [ -e "${'$'}_JDK" ] && _binds="${'$'}_binds -b ${'$'}_JDK"
          echo "${'$'}_binds"
        }

        proot_common_env() {
          _JDK=${'$'}(aidev_resolve_jdk)
          _JDK_BIN="${'$'}_JDK/bin:"
          echo "HOME=/root AIDEV_HOME=/host-home AIDEV_VERSION=${'$'}{AIDEV_VERSION:-unknown} AIDEV_REALM=U ANDROID_SDK_ROOT=/host-home/android-sdk ${'$'}{_JDK:+JAVA_HOME=${'$'}_JDK }GRADLE_USER_HOME=/host-home/gradle-cache PATH=${'$'}_JDK_BIN/root/.opencode/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin:/host-home/dev-env/bin:/host-home/android-sdk/cmdline-tools/latest/bin TERM=${'$'}{TERM:-xterm-256color} LANG=C.UTF-8 LC_ALL=C.UTF-8"
        }

        enter_ubuntu() {
          if [ "${'$'}{AIDEV_IN_ROOTFS:-0}" = "1" ]; then
            printf '已在终端环境（Ubuntu），无需重复进入。\n'
            return 0
          fi
          printf '\033[32m已进入 Ubuntu 终端环境\033[0m\n'
          has_ubuntu || install_ubuntu --fast || return ${'$'}?
          ensure_android_groups "${'$'}AIDEV_ROOTFS"
          ensure_ubuntu_helpers
          shell="/bin/bash"
          [ -x "${'$'}AIDEV_ROOTFS/bin/bash" ] || shell="/bin/sh"
          cd "${'$'}AIDEV_HOME" || exit 1
          mkdir -p "${'$'}{PROOT_TMP_DIR:-${'$'}AIDEV_HOME/proot-tmp}"
          sleep 0.05
          eval exec "${'$'}AIDEV_PROOT" --link2symlink -0 -r "${'$'}AIDEV_ROOTFS" \
            $(proot_common_binds) \
            -w /workspace /usr/bin/env -i \
            $(proot_common_env) "${'$'}shell" -l
        }

        run_ubuntu_command() {
          # 用户覆盖层优先：自定义命令放 AIDEV_OVERRIDES/bin 时优先于出厂脚本
          _ru_all="${'$'}*"
          _ru_cmd="${'$'}{_ru_all%% *}"
          _ru_rest="${'$'}{_ru_all#* }"
          case "${'$'}{_ru_cmd}" in
            /*) _ru_base="$(basename "${'$'}{_ru_cmd}")" ;;
            *)  _ru_base="${'$'}{_ru_cmd}" ;;
          esac
          if [ -n "${'$'}{AIDEV_OVERRIDES:-}" ] && [ -x "${'$'}{AIDEV_OVERRIDES}/${'$'}{_ru_base}" ]; then
            _ru_cmd="${'$'}{AIDEV_OVERRIDES}/${'$'}{_ru_base}"
          fi
          _ru_line="${'$'}{_ru_cmd} ${'$'}{_ru_rest}"
          # 已在 rootfs 内：直接就地执行，避免嵌套 proot 及指向宿主绝对路径失败
          if [ "${'$'}{AIDEV_IN_ROOTFS:-0}" = "1" ]; then
            exec /bin/sh -lc "${'$'}{_ru_line}" 2>/dev/null
          fi
          has_ubuntu || install_ubuntu --fast || return ${'$'}?
          ensure_android_groups "${'$'}AIDEV_ROOTFS"
          ensure_ubuntu_helpers
          cd "${'$'}AIDEV_HOME" || exit 1
          eval exec "${'$'}AIDEV_PROOT" --link2symlink -0 -r "${'$'}AIDEV_ROOTFS" \
            $(proot_common_binds) \
            -w /root /usr/bin/env -i \
            $(proot_common_env) /bin/sh -lc "${'$'}{_ru_line}" 2>/dev/null
        }

        fix_bashrc() {
          local bashrc="${'$'}AIDEV_ROOTFS/root/.bashrc"
          if [ -f "${'$'}{bashrc}.bak" ]; then
            cp "${'$'}{bashrc}.bak" "${'$'}bashrc"
            echo "[OK] 已从 .bashrc.bak 恢复"
          elif [ -f "${'$'}bashrc" ]; then
            echo "[OK] 无备份文件，当前 .bashrc 存在，无需修复"
          else
            echo "[INFO] .bashrc 不存在，创建默认 .bashrc"
            mkdir -p "$(dirname "${'$'}bashrc")"
            cat > "${'$'}bashrc" << 'AIDEV_PWD_HOOK_EOF'
# AIDEV_PWD_HOOK_BEGIN
. /host-home/.aidevrc
# Replace /system/bin/sh -> /bin/sh in .aidevrc function bodies (pure bash, no sed)
_p=/system/bin/sh _q=/bin/sh
eval "${'$'}(
              declare -f |
              while IFS= read -r __l; do
                case "${'$'}__l" in
                  *${'$'}_p*) printf '%s\n' "${'$'}{__l//${'$'}_p/${'$'}_q}" ;;
                  *) printf '%s\n' "${'$'}__l" ;;
                esac
              done
)"
unset _p _q
_p="${'$'}PATH"; PATH=""
while [ -n "${'$'}_p" ]; do _e="${'$'}{_p%%:*}"; case "${'$'}_e" in /system/*) ;; *) PATH="${'$'}{PATH:+${'$'}PATH:}${'$'}_e" ;; esac; [ "${'$'}_p" = "${'$'}_e" ] && _p="" || _p="${'$'}{_p#*:}"; done
unset _p _e
aidev_write_pwd() {
  pwd > /host-home/.aidev-current-pwd 2>/dev/null || true
}
case "${'$'}{PROMPT_COMMAND:-}" in
  *aidev_write_pwd*) ;;
  *) PROMPT_COMMAND="aidev_write_pwd${'$'}{PROMPT_COMMAND:+;${'$'}PROMPT_COMMAND}" ;;
esac
aidev_write_pwd
# AIDEV_PWD_HOOK_END
AIDEV_PWD_HOOK_EOF
            echo "[OK] 默认 .bashrc 已创建"
          fi
        }

        case "${'$'}cmd" in
          ubuntu) enter_ubuntu "${'$'}@" ;;
          install-ubuntu) install_ubuntu "${'$'}@" ;;
          aidev-doctor) aidev_doctor_android ;;
          setup-dev-env) run_ubuntu_command "/usr/local/bin/setup-dev-env" "${'$'}@" ;;
          aidev-verify-run) run_ubuntu_command "/usr/local/bin/aidev-verify-run" "${'$'}@" ;;
          aidev-autoinstall) run_ubuntu_command "/usr/local/bin/aidev-autoinstall" "${'$'}@" ;;
          aidev-apk-info) run_ubuntu_command "/usr/local/bin/aidev-apk-info" "${'$'}@" ;;
          aidev-gen) run_ubuntu_command "/usr/local/bin/aidev-gen" "${'$'}@" ;;
          aidev-error-why) run_ubuntu_command "/usr/local/bin/aidev-error-why" "${'$'}@" ;;
          aidev-index) run_ubuntu_command "/usr/local/bin/aidev-index" "${'$'}@" ;;
          android-sh) run_ubuntu_command "/usr/local/bin/android-sh" "${'$'}@" ;;
          aidev-clean) run_ubuntu_command "/usr/local/bin/aidev-clean" "${'$'}@" ;;
          fix-bashrc) fix_bashrc ;;
          check-dev-env) run_ubuntu_command "/usr/local/bin/check-dev-env" ;;
          repair-dev-env) run_ubuntu_command "/usr/local/bin/repair-dev-env" ;;
          aidev-backup) run_ubuntu_command "/usr/local/bin/aidev-backup" "${'$'}@" ;;
          aidev-logcat) run_ubuntu_command "/usr/local/bin/aidev-logcat" "${'$'}@" ;;
          aidev-anr) run_ubuntu_command "/usr/local/bin/aidev-anr" "${'$'}@" ;;
          aidev-tombstone) run_ubuntu_command "/usr/local/bin/aidev-tombstone" "${'$'}@" ;;
          aidev-crash-why) run_ubuntu_command "/usr/local/bin/aidev-crash-why" "${'$'}@" ;;
          aidev-dumpsys) run_ubuntu_command "/usr/local/bin/aidev-dumpsys" "${'$'}@" ;;
          aidev-auto-bootstrap)
            if has_ubuntu; then
              ubuntu_logo "自动进入环境"
            else
              ubuntu_logo "正在初始化环境"
            fi
            enter_ubuntu "${'$'}@"
            ;;
          *) echo "未知命令：${'$'}cmd"; exit 2 ;;
        esac
        """.trimIndent() + "\n"
    }

    /**
     * 预置 create-compose-project 所需的模板目录（gradlew + gradle-wrapper.jar）。
     * 独立于脚本版本门控：幂等补齐——仅当 gradlew 不存在时才复制，确保已部署设备也能获得。
     * 终端环境内 ${'$'}HOME=/root，对应 rootfs 的 root/；脚本据此解析 TEMPLATE_DIR=/root/.gradle/template-wrapper。
     */
    private fun ensureTemplateWrapper(activity: android.app.Activity, rootfs: java.io.File) {
        val templateRoot = java.io.File(rootfs, "root/.gradle/template-wrapper")
        val gradlewDst = java.io.File(templateRoot, "gradlew")
        if (gradlewDst.exists()) {
            android.util.Log.d("AIDev", "模板目录已存在，跳过预置: ${templateRoot.absolutePath}")
            return
        }
        runCatching {
            templateRoot.mkdirs()
            fun copyAssetRel(rel: String, dst: java.io.File, exec: Boolean = false) {
                activity.assets.open("scripts/template-wrapper/$rel").use { input ->
                    dst.outputStream().use { output -> input.copyTo(output) }
                }
                if (exec) dst.setExecutable(true)
            }
            copyAssetRel("gradlew", gradlewDst, exec = true)
            copyAssetRel("gradlew.real", java.io.File(templateRoot, "gradlew.real"), exec = true)
            copyAssetRel("gradlew.bat", java.io.File(templateRoot, "gradlew.bat"))
            val wrapperDir = java.io.File(templateRoot, "gradle/wrapper").apply { mkdirs() }
            copyAssetRel("gradle/wrapper/gradle-wrapper.jar", java.io.File(wrapperDir, "gradle-wrapper.jar"))
            android.util.Log.d("AIDev", "已预置模板目录: ${templateRoot.absolutePath}")
        }.onFailure { e -> android.util.Log.w("AIDev", "无法预置模板目录: ${e.message}") }
    }
