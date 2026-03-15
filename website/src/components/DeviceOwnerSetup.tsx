import { useState, useCallback } from 'react'
import { useInView } from '../hooks/useInView'
import { Adb, AdbDaemonTransport } from '@yume-chan/adb'
import { AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb'
import AdbWebCredentialStore from '@yume-chan/adb-credential-web'

type Stage = 'idle' | 'connecting' | 'authenticating' | 'executing' | 'success' | 'error'

const DPM_COMMAND = 'dpm set-device-owner com.andforce.andclaw/.DeviceAdminReceiver'

const credentialStore = new AdbWebCredentialStore('Andclaw WebUSB')

function getManager(): AdbDaemonWebUsbDeviceManager | undefined {
  return AdbDaemonWebUsbDeviceManager.BROWSER
}

export default function DeviceOwnerSetup() {
  const { ref, isVisible } = useInView()
  const [stage, setStage] = useState<Stage>('idle')
  const [log, setLog] = useState<string[]>([])
  const [errorMsg, setErrorMsg] = useState('')

  const appendLog = useCallback((msg: string) => {
    setLog(prev => [...prev, msg])
  }, [])

  const reset = useCallback(() => {
    setStage('idle')
    setLog([])
    setErrorMsg('')
  }, [])

  const execute = useCallback(async () => {
    const manager = getManager()
    if (!manager) {
      setErrorMsg('当前浏览器不支持 WebUSB，请使用 Chrome / Edge 等 Chromium 内核浏览器')
      setStage('error')
      return
    }

    let adb: Adb | undefined

    try {
      setStage('connecting')
      setLog([])
      setErrorMsg('')
      appendLog('正在请求 USB 设备权限...')

      const device = await manager.requestDevice()
      if (!device) {
        appendLog('未选择设备，操作已取消')
        setStage('idle')
        return
      }

      appendLog(`已选择设备: ${device.serial}`)
      appendLog('正在建立 USB 连接...')

      const connection = await device.connect()

      setStage('authenticating')
      appendLog('正在进行 ADB 认证...')
      appendLog('如果设备弹出「允许 USB 调试」对话框，请点击「允许」')

      const transport = await AdbDaemonTransport.authenticate({
        serial: device.serial,
        connection,
        credentialStore,
      })

      adb = new Adb(transport)
      appendLog('ADB 连接已建立')

      setStage('executing')
      appendLog(`正在执行: ${DPM_COMMAND}`)

      const shellProtocol = adb.subprocess.shellProtocol
      if (shellProtocol) {
        const result = await shellProtocol.spawnWaitText(DPM_COMMAND)
        if (result.stdout.trim()) appendLog(`stdout: ${result.stdout.trim()}`)
        if (result.stderr.trim()) appendLog(`stderr: ${result.stderr.trim()}`)
        if (result.exitCode === 0) {
          setStage('success')
          appendLog('Device Owner 设置成功！')
        } else {
          setErrorMsg(`命令执行失败 (exit code: ${result.exitCode})`)
          setStage('error')
        }
      } else {
        const output = await adb.subprocess.noneProtocol.spawnWaitText(DPM_COMMAND)
        if (output.trim()) appendLog(`output: ${output.trim()}`)
        if (output.toLowerCase().includes('success') || !output.toLowerCase().includes('error')) {
          setStage('success')
          appendLog('Device Owner 设置成功！')
        } else {
          setErrorMsg(output.trim())
          setStage('error')
        }
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      appendLog(`错误: ${msg}`)
      setErrorMsg(msg)
      setStage('error')
    } finally {
      if (adb) {
        try { await adb.close() } catch { /* ignore */ }
      }
    }
  }, [appendLog])

  const isWebUsbSupported = !!getManager()

  return (
    <section id="setup" className="py-24 px-6" ref={ref}>
      <div className="max-w-4xl mx-auto">
        <h2
          className={`fade-in-up font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold text-center mb-4 ${isVisible ? 'is-visible' : ''}`}
        >
          <span className="text-neon-green text-glow-cyan">在线</span>
          <span className="text-white">激活</span>
        </h2>
        <p
          className={`fade-in-up text-gray-400 text-center mb-12 max-w-2xl mx-auto ${isVisible ? 'is-visible' : ''}`}
          style={{ transitionDelay: '100ms' }}
        >
          无需安装 ADB 工具，直接在浏览器中通过 USB 连接激活 Device Owner 模式
        </p>

        <div
          className={`fade-in-up rounded-xl border border-dark-border/50 bg-dark-card/60 backdrop-blur-sm p-8 ${isVisible ? 'is-visible' : ''}`}
          style={{ transitionDelay: '200ms' }}
        >
          {/* 步骤指引 */}
          <div className="grid grid-cols-1 md:grid-cols-3 gap-6 mb-8">
            <StepCard
              step={1}
              title="连接设备"
              desc="用 USB 数据线连接手机到电脑，确保已开启「USB 调试」"
              active={stage === 'idle' || stage === 'connecting'}
              done={stage !== 'idle' && stage !== 'connecting'}
            />
            <StepCard
              step={2}
              title="授权连接"
              desc="在手机上弹出的对话框中点击「允许 USB 调试」"
              active={stage === 'authenticating'}
              done={stage === 'executing' || stage === 'success'}
            />
            <StepCard
              step={3}
              title="自动执行"
              desc="浏览器自动执行 ADB 命令激活 Device Owner"
              active={stage === 'executing'}
              done={stage === 'success'}
            />
          </div>

          {/* 前置条件提醒 */}
          <div className="mb-6 rounded-lg border border-neon-purple/20 bg-neon-purple/5 px-5 py-4">
            <p className="text-sm text-gray-300 leading-relaxed">
              <span className="text-neon-purple font-semibold">前置条件：</span>
              设备已<strong className="text-white">恢复出厂设置</strong>，且已在 <em>设置 &gt; 开发者选项</em> 中开启
              <strong className="text-white"> USB 调试</strong>。
              由于 Android 安全限制，未恢复出厂设置的设备无法启用 Device Owner。
            </p>
          </div>

          {/* 要执行的命令 */}
          <div className="mb-6 rounded-lg bg-dark-base/80 border border-dark-border/30 px-5 py-3 font-mono text-sm text-neon-cyan overflow-x-auto">
            <span className="text-gray-500 select-none">$ </span>{DPM_COMMAND}
          </div>

          {/* 操作按钮 */}
          <div className="flex items-center gap-4 mb-6">
            {stage === 'idle' || stage === 'error' ? (
              <button
                onClick={execute}
                disabled={!isWebUsbSupported}
                className="inline-flex items-center gap-2 px-8 py-3.5 rounded-lg font-semibold text-dark-base bg-neon-green hover:bg-neon-green/90 transition-all glow-green hover:scale-105 disabled:opacity-40 disabled:hover:scale-100 disabled:cursor-not-allowed"
              >
                <UsbIcon />
                {stage === 'error' ? '重试' : '连接设备并激活'}
              </button>
            ) : stage === 'success' ? (
              <button
                onClick={reset}
                className="inline-flex items-center gap-2 px-8 py-3.5 rounded-lg font-semibold border border-neon-cyan/50 text-neon-cyan hover:bg-neon-cyan/10 transition-all"
              >
                完成
              </button>
            ) : (
              <div className="inline-flex items-center gap-3 px-8 py-3.5 text-gray-300">
                <Spinner />
                {stage === 'connecting' && '正在连接...'}
                {stage === 'authenticating' && '等待设备授权...'}
                {stage === 'executing' && '正在执行命令...'}
              </div>
            )}

            {!isWebUsbSupported && stage === 'idle' && (
              <span className="text-sm text-red-400">当前浏览器不支持 WebUSB</span>
            )}
          </div>

          {/* 日志输出 */}
          {log.length > 0 && (
            <div className="rounded-lg bg-dark-base/80 border border-dark-border/30 p-4 max-h-60 overflow-y-auto font-mono text-xs leading-relaxed">
              {log.map((line, i) => (
                <div key={i} className={line.startsWith('错误') || line.startsWith('stderr') ? 'text-red-400' : 'text-gray-400'}>
                  <span className="text-gray-600 select-none">[{String(i + 1).padStart(2, '0')}] </span>
                  {line}
                </div>
              ))}
              {stage === 'success' && (
                <div className="text-neon-green mt-1">
                  <span className="text-gray-600 select-none">[OK] </span>
                  Device Owner 模式已激活，现在可以拔掉 USB 线了
                </div>
              )}
            </div>
          )}

          {/* 错误信息 */}
          {stage === 'error' && errorMsg && (
            <div className="mt-4 rounded-lg border border-red-500/30 bg-red-500/5 px-5 py-3">
              <p className="text-sm text-red-400">{errorMsg}</p>
            </div>
          )}
        </div>

        {/* 浏览器兼容性提示 */}
        <p
          className={`fade-in-up text-xs text-gray-600 text-center mt-6 ${isVisible ? 'is-visible' : ''}`}
          style={{ transitionDelay: '300ms' }}
        >
          需要 Chrome 61+ / Edge 79+ 等 Chromium 内核浏览器，且页面需通过 HTTPS 或 localhost 访问
        </p>
      </div>
    </section>
  )
}

function StepCard({ step, title, desc, active, done }: {
  step: number; title: string; desc: string; active: boolean; done: boolean
}) {
  const borderColor = done
    ? 'border-neon-green/40'
    : active
      ? 'border-neon-cyan/40'
      : 'border-dark-border/30'
  const numColor = done
    ? 'bg-neon-green/20 text-neon-green'
    : active
      ? 'bg-neon-cyan/20 text-neon-cyan'
      : 'bg-dark-border/20 text-gray-500'

  return (
    <div className={`rounded-lg border ${borderColor} bg-dark-base/40 p-4 transition-colors duration-300`}>
      <div className="flex items-center gap-3 mb-2">
        <span className={`w-7 h-7 rounded-full ${numColor} flex items-center justify-center text-sm font-bold`}>
          {done ? <CheckIcon /> : step}
        </span>
        <span className={`font-semibold text-sm ${done || active ? 'text-white' : 'text-gray-500'}`}>{title}</span>
      </div>
      <p className="text-xs text-gray-500 leading-relaxed">{desc}</p>
    </div>
  )
}

function UsbIcon() {
  return (
    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M12 2v10m0 0l3-3m-3 3l-3-3M7 17a2 2 0 100-4 2 2 0 000 4zm10 0a2 2 0 100-4 2 2 0 000 4zM7 17v2a2 2 0 002 2h6a2 2 0 002-2v-2" />
    </svg>
  )
}

function CheckIcon() {
  return (
    <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={3}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  )
}

function Spinner() {
  return (
    <svg className="w-5 h-5 animate-spin text-neon-cyan" fill="none" viewBox="0 0 24 24">
      <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
      <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
    </svg>
  )
}
