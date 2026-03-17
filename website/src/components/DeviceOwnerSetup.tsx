import { useState, useCallback, useRef, useEffect } from 'react'
import { Adb, AdbDaemonTransport } from '@yume-chan/adb'
import { AdbDaemonWebUsbDeviceManager } from '@yume-chan/adb-daemon-webusb'
import AdbWebCredentialStore from '@yume-chan/adb-credential-web'

const APK_URL = 'https://github.com/andforce/Andclaw/releases/download/1.0.2/1.0.2-102-release.apk'
const DPM_COMMAND = 'dpm set-device-owner com.andforce.andclaw/.DeviceAdminReceiver'
const DEVICE_TEMP_PATH = '/data/local/tmp/andclaw.apk'

type Stage =
  | 'idle'
  | 'connecting'
  | 'authenticating'
  | 'installing'
  | 'activating'
  | 'success'
  | 'error'

const STAGE_ORDER: Record<Stage, number> = {
  idle: 0,
  connecting: 1,
  authenticating: 2,
  installing: 3,
  activating: 4,
  success: 5,
  error: -1,
}

const credentialStore = new AdbWebCredentialStore('Andclaw WebUSB')

function getManager() {
  return AdbDaemonWebUsbDeviceManager.BROWSER
}

export default function InstallPage() {
  const [stage, setStage] = useState<Stage>('idle')
  const [log, setLog] = useState<string[]>([])
  const [errorMsg, setErrorMsg] = useState('')
  const [apkData, setApkData] = useState<Uint8Array | null>(null)
  const [apkFileName, setApkFileName] = useState('')
  const adbRef = useRef<Adb | null>(null)
  const logEndRef = useRef<HTMLDivElement>(null)
  const fileInputRef = useRef<HTMLInputElement>(null)

  const appendLog = useCallback((msg: string) => {
    setLog(prev => [...prev, msg])
  }, [])

  useEffect(() => {
    logEndRef.current?.scrollIntoView({ behavior: 'smooth' })
  }, [log])

  const reset = useCallback(() => {
    setStage('idle')
    setLog([])
    setErrorMsg('')
  }, [])

  const handleFileSelect = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    const reader = new FileReader()
    reader.onload = () => {
      setApkData(new Uint8Array(reader.result as ArrayBuffer))
      setApkFileName(file.name)
    }
    reader.readAsArrayBuffer(file)
  }, [])

  const execute = useCallback(async () => {
    const manager = getManager()
    if (!manager) {
      setErrorMsg('当前浏览器不支持 WebUSB，请使用 Chrome / Edge 等 Chromium 内核浏览器')
      setStage('error')
      return
    }
    if (!apkData) {
      setErrorMsg('请先选择 APK 文件')
      setStage('error')
      return
    }

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

      const adb = new Adb(transport)
      adbRef.current = adb
      appendLog('ADB 连接已建立')

      // 推送并安装 APK
      setStage('installing')
      appendLog(`正在推送 APK 到设备 (${(apkData.byteLength / 1024 / 1024).toFixed(1)} MB)...`)

      const sync = await adb.sync()
      try {
        const file = new ReadableStream<Uint8Array>({
          start(controller) {
            const CHUNK_SIZE = 64 * 1024
            for (let i = 0; i < apkData.length; i += CHUNK_SIZE) {
              controller.enqueue(apkData.slice(i, Math.min(i + CHUNK_SIZE, apkData.length)))
            }
            controller.close()
          },
        })
        await sync.write({
          filename: DEVICE_TEMP_PATH,
          // eslint-disable-next-line @typescript-eslint/no-explicit-any
          file: file as any,
        })
      } finally {
        await sync.dispose()
      }

      appendLog('APK 推送完成，正在安装...')

      const shellProtocol = adb.subprocess.shellProtocol
      if (shellProtocol) {
        const installResult = await shellProtocol.spawnWaitText(`pm install -r ${DEVICE_TEMP_PATH}`)
        if (installResult.stdout.trim()) appendLog(`stdout: ${installResult.stdout.trim()}`)
        if (installResult.stderr.trim()) appendLog(`stderr: ${installResult.stderr.trim()}`)
        if (installResult.exitCode !== 0) {
          throw new Error(`APK 安装失败 (exit code: ${installResult.exitCode})`)
        }
        appendLog('APK 安装成功')
        await shellProtocol.spawnWaitText(`rm -f ${DEVICE_TEMP_PATH}`)
      } else {
        const output = await adb.subprocess.noneProtocol.spawnWaitText(`pm install -r ${DEVICE_TEMP_PATH}`)
        if (output.trim()) appendLog(`output: ${output.trim()}`)
        if (output.toLowerCase().includes('failure') || output.toLowerCase().includes('error')) {
          throw new Error(`APK 安装失败: ${output.trim()}`)
        }
        appendLog('APK 安装成功')
        await adb.subprocess.noneProtocol.spawnWaitText(`rm -f ${DEVICE_TEMP_PATH}`)
      }

      // 设置 Device Owner
      setStage('activating')
      appendLog(`正在执行: ${DPM_COMMAND}`)

      if (shellProtocol) {
        const dpmResult = await shellProtocol.spawnWaitText(DPM_COMMAND)
        if (dpmResult.stdout.trim()) appendLog(`stdout: ${dpmResult.stdout.trim()}`)
        if (dpmResult.stderr.trim()) appendLog(`stderr: ${dpmResult.stderr.trim()}`)
        if (dpmResult.exitCode === 0) {
          setStage('success')
          appendLog('Device Owner 设置成功！')
        } else {
          throw new Error(`Device Owner 设置失败 (exit code: ${dpmResult.exitCode})`)
        }
      } else {
        const output = await adb.subprocess.noneProtocol.spawnWaitText(DPM_COMMAND)
        if (output.trim()) appendLog(`output: ${output.trim()}`)
        if (output.toLowerCase().includes('success') || !output.toLowerCase().includes('error')) {
          setStage('success')
          appendLog('Device Owner 设置成功！')
        } else {
          throw new Error(output.trim())
        }
      }
    } catch (e) {
      const msg = e instanceof Error ? e.message : String(e)
      appendLog(`错误: ${msg}`)
      setErrorMsg(msg)
      setStage('error')
    } finally {
      if (adbRef.current) {
        try { await adbRef.current.close() } catch { /* ignore */ }
        adbRef.current = null
      }
    }
  }, [appendLog, apkData])

  const isWebUsbSupported = !!getManager()
  const order = STAGE_ORDER[stage]

  const steps = [
    { label: '连接设备', desc: '通过 USB 建立 ADB 连接', minOrder: 1, maxOrder: 2 },
    { label: '安装应用', desc: '推送并安装 APK', minOrder: 3, maxOrder: 3 },
    { label: '激活权限', desc: '设置 Device Owner', minOrder: 4, maxOrder: 4 },
  ]

  return (
    <div className="min-h-screen bg-dark-base bg-grid bg-circuit">
      <header className="fixed top-0 left-0 right-0 z-50 bg-dark-base/80 backdrop-blur-md border-b border-dark-border/50">
        <div className="max-w-4xl mx-auto px-6 h-14 flex items-center justify-between">
          <a href="#" className="flex items-center gap-2">
            <img src="/icon.png" alt="Andclaw" className="w-7 h-7" />
            <span className="font-[family-name:var(--font-family-display)] text-sm font-bold">
              <span className="text-white">And</span>
              <span className="text-neon-cyan">claw</span>
            </span>
          </a>
          <a
            href="#"
            className="text-sm text-gray-400 hover:text-neon-cyan transition-colors"
          >
            ← 返回首页
          </a>
        </div>
      </header>

      <main className="pt-24 pb-16 px-6 max-w-4xl mx-auto">
        <div className="text-center mb-12">
          <h1 className="font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold mb-4">
            <span className="text-neon-green text-glow-cyan">在线</span>
            <span className="text-white">安装</span>
          </h1>
          <p className="text-gray-400 max-w-2xl mx-auto">
            无需安装 ADB 工具，直接在浏览器中通过 USB 连接安装应用并激活 Device Owner 模式
          </p>
        </div>

        {/* 前置条件 */}
        <div className="mb-10 rounded-xl border border-neon-purple/20 bg-neon-purple/5 p-6">
          <h2 className="text-lg font-semibold text-neon-purple mb-4 flex items-center gap-2">
            <ClipboardIcon />
            准备工作
          </h2>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
            <PrereqCard
              step={1}
              title="恢复出厂设置"
              items={[
                '进入 设置 > 系统 > 重置',
                '选择「恢复出厂设置」',
                '等待设备重启并完成初始设置',
              ]}
            />
            <PrereqCard
              step={2}
              title="开启开发者模式"
              items={[
                '进入 设置 > 关于手机',
                '连续点击「版本号」7 次',
                '看到提示「您已进入开发者模式」',
              ]}
            />
            <PrereqCard
              step={3}
              title="启用 USB 调试"
              items={[
                '进入 设置 > 开发者选项',
                '开启「USB 调试」开关',
                '用 USB 数据线连接电脑',
              ]}
            />
          </div>
          <p className="mt-4 text-xs text-gray-500">
            ⚠ Android 安全限制：未恢复出厂设置的设备无法启用 Device Owner 模式
          </p>
        </div>

        {/* 下载 APK */}
        <div className="mb-10 rounded-xl border border-neon-cyan/20 bg-neon-cyan/5 p-6">
          <h2 className="text-lg font-semibold text-neon-cyan mb-4 flex items-center gap-2">
            <DownloadIcon />
            下载安装包
          </h2>
          <div className="flex flex-col sm:flex-row items-start sm:items-center gap-4">
            <a
              href={APK_URL}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex items-center gap-2 px-6 py-2.5 rounded-lg font-semibold border border-neon-cyan/40 text-neon-cyan hover:bg-neon-cyan/10 transition-colors text-sm"
            >
              <DownloadIcon />
              下载 APK
            </a>
            <div className="flex items-center gap-3">
              <button
                onClick={() => fileInputRef.current?.click()}
                className="inline-flex items-center gap-2 px-6 py-2.5 rounded-lg font-semibold border border-dark-border/50 text-gray-300 hover:border-neon-green/40 hover:text-neon-green transition-colors text-sm"
              >
                <FileIcon />
                选择已下载的 APK
              </button>
              {apkFileName && (
                <span className="text-sm text-neon-green flex items-center gap-1.5">
                  <CheckIcon />
                  {apkFileName} ({(apkData!.byteLength / 1024 / 1024).toFixed(1)} MB)
                </span>
              )}
            </div>
            <input
              ref={fileInputRef}
              type="file"
              accept=".apk"
              onChange={handleFileSelect}
              className="hidden"
            />
          </div>
          <p className="mt-3 text-xs text-gray-500">
            先点击「下载 APK」保存到电脑，再点击「选择已下载的 APK」选中文件
          </p>
        </div>

        {/* 主操作区 */}
        <div className="rounded-xl border border-dark-border/50 bg-dark-card/60 backdrop-blur-sm p-8">
          <div className="grid grid-cols-3 gap-4 mb-8">
            {steps.map((step, i) => {
              const done = order > step.maxOrder
              const active = stage !== 'error' && order >= step.minOrder && order <= step.maxOrder
              return (
                <StepCard
                  key={step.label}
                  step={i + 1}
                  title={step.label}
                  desc={step.desc}
                  active={active}
                  done={done}
                />
              )
            })}
          </div>

          <div className="flex items-center gap-4 mb-6">
            {stage === 'idle' || stage === 'error' ? (
              <button
                onClick={execute}
                disabled={!isWebUsbSupported || !apkData}
                className="inline-flex items-center gap-2 px-8 py-3.5 rounded-lg font-semibold text-dark-base bg-neon-green hover:bg-neon-green/90 transition-all glow-green hover:scale-105 disabled:opacity-40 disabled:hover:scale-100 disabled:cursor-not-allowed"
              >
                <UsbIcon />
                {stage === 'error' ? '重试' : '开始安装'}
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
                {stage === 'installing' && '正在安装...'}
                {stage === 'activating' && '正在激活 Device Owner...'}
              </div>
            )}

            {!isWebUsbSupported && stage === 'idle' && (
              <span className="text-sm text-red-400">当前浏览器不支持 WebUSB</span>
            )}
            {isWebUsbSupported && !apkData && stage === 'idle' && (
              <span className="text-sm text-gray-500">请先下载并选择 APK 文件</span>
            )}
          </div>

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
                  安装并激活完成，现在可以拔掉 USB 线了
                </div>
              )}
              <div ref={logEndRef} />
            </div>
          )}

          {stage === 'error' && errorMsg && (
            <div className="mt-4 rounded-lg border border-red-500/30 bg-red-500/5 px-5 py-3">
              <p className="text-sm text-red-400">{errorMsg}</p>
            </div>
          )}
        </div>

        <p className="text-xs text-gray-600 text-center mt-6">
          需要 Chrome 61+ / Edge 79+ 等 Chromium 内核浏览器，且页面需通过 HTTPS 或 localhost 访问
        </p>
      </main>
    </div>
  )
}

function PrereqCard({ step, title, items }: { step: number; title: string; items: string[] }) {
  return (
    <div className="rounded-lg border border-neon-purple/15 bg-dark-base/40 p-4">
      <div className="flex items-center gap-3 mb-3">
        <span className="w-7 h-7 rounded-full bg-neon-purple/20 text-neon-purple flex items-center justify-center text-sm font-bold">
          {step}
        </span>
        <span className="font-semibold text-sm text-white">{title}</span>
      </div>
      <ul className="space-y-1.5">
        {items.map((item, i) => (
          <li key={i} className="text-xs text-gray-400 leading-relaxed flex items-start gap-2">
            <span className="text-neon-purple/60 mt-0.5">›</span>
            {item}
          </li>
        ))}
      </ul>
    </div>
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

function ClipboardIcon() {
  return (
    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 5H7a2 2 0 00-2 2v12a2 2 0 002 2h10a2 2 0 002-2V7a2 2 0 00-2-2h-2M9 5a2 2 0 002 2h2a2 2 0 002-2M9 5a2 2 0 012-2h2a2 2 0 012 2m-6 9l2 2 4-4" />
    </svg>
  )
}

function DownloadIcon() {
  return (
    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M4 16v1a3 3 0 003 3h10a3 3 0 003-3v-1m-4-4l-4 4m0 0l-4-4m4 4V4" />
    </svg>
  )
}

function FileIcon() {
  return (
    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M7 21h10a2 2 0 002-2V9.414a1 1 0 00-.293-.707l-5.414-5.414A1 1 0 0012.586 3H7a2 2 0 00-2 2v14a2 2 0 002 2z" />
    </svg>
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
