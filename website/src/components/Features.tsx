import { useInView } from '../hooks/useInView'

const features = [
  {
    icon: <BrainIcon />,
    title: 'AI 驱动',
    desc: '支持 Kimi / OpenAI 兼容 API，用自然语言告诉手机你想做什么，AI 自主规划并执行。',
    color: 'cyan' as const,
  },
  {
    icon: <EyeIcon />,
    title: '屏幕感知',
    desc: '实时读取 UI 层次结构，WebView/浏览器场景自动截图辅助视觉分析，真正"看懂"屏幕。',
    color: 'purple' as const,
  },
  {
    icon: <HandIcon />,
    title: '拟人操作',
    desc: '模拟点击、滑动、长按、文本输入等手势操作，像真人一样与任何 App 交互。',
    color: 'green' as const,
  },
  {
    icon: <CameraIcon />,
    title: '多媒体能力',
    desc: '拍照、录像、录屏、截图、音量控制，全方位多媒体操作一句话搞定。',
    color: 'cyan' as const,
  },
  {
    icon: <TelegramIcon />,
    title: '远程控制',
    desc: '通过 Telegram Bot 远程下发指令，实时接收截图和录像，不在身边也能操控设备。',
    color: 'purple' as const,
  },
  {
    icon: <ShieldIcon />,
    title: '企业管控',
    desc: 'Device Owner 模式下支持静默安装/卸载、Kiosk 锁定、设备策略管理等企业级能力。',
    color: 'green' as const,
  },
]

const colorMap = {
  cyan: {
    border: 'border-neon-cyan/20 hover:border-neon-cyan/50',
    iconBg: 'bg-neon-cyan/10',
    iconText: 'text-neon-cyan',
    glow: 'hover:shadow-[0_0_20px_rgba(34,211,238,0.15)]',
  },
  purple: {
    border: 'border-neon-purple/20 hover:border-neon-purple/50',
    iconBg: 'bg-neon-purple/10',
    iconText: 'text-neon-purple',
    glow: 'hover:shadow-[0_0_20px_rgba(139,92,246,0.15)]',
  },
  green: {
    border: 'border-neon-green/20 hover:border-neon-green/50',
    iconBg: 'bg-neon-green/10',
    iconText: 'text-neon-green',
    glow: 'hover:shadow-[0_0_20px_rgba(74,222,128,0.15)]',
  },
}

export default function Features() {
  const { ref, isVisible } = useInView()

  return (
    <section id="features" className="py-24 px-6" ref={ref}>
      <div className="max-w-6xl mx-auto">
        <h2 className="font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold text-center mb-4">
          <span className="text-neon-cyan text-glow-cyan">核心</span>
          <span className="text-white">特性</span>
        </h2>
        <p className="text-gray-400 text-center mb-16 max-w-xl mx-auto">
          无需 Root，无需电脑，AI 直接在设备上理解屏幕并执行操作
        </p>

        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
          {features.map((f, i) => {
            const c = colorMap[f.color]
            return (
              <div
                key={f.title}
                className={`fade-in-up rounded-xl border bg-dark-card/60 backdrop-blur-sm p-6 transition-all duration-300 ${c.border} ${c.glow} ${isVisible ? 'is-visible' : ''}`}
                style={{ transitionDelay: `${i * 100}ms` }}
              >
                <div className={`w-12 h-12 rounded-lg ${c.iconBg} flex items-center justify-center mb-4`}>
                  <span className={c.iconText}>{f.icon}</span>
                </div>
                <h3 className="text-lg font-semibold text-white mb-2">{f.title}</h3>
                <p className="text-gray-400 text-sm leading-relaxed">{f.desc}</p>
              </div>
            )
          })}
        </div>
      </div>
    </section>
  )
}

function BrainIcon() {
  return (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09zM18.259 8.715L18 9.75l-.259-1.035a3.375 3.375 0 00-2.455-2.456L14.25 6l1.036-.259a3.375 3.375 0 002.455-2.456L18 2.25l.259 1.035a3.375 3.375 0 002.455 2.456L21.75 6l-1.036.259a3.375 3.375 0 00-2.455 2.456zM16.894 20.567L16.5 21.75l-.394-1.183a2.25 2.25 0 00-1.423-1.423L13.5 18.75l1.183-.394a2.25 2.25 0 001.423-1.423l.394-1.183.394 1.183a2.25 2.25 0 001.423 1.423l1.183.394-1.183.394a2.25 2.25 0 00-1.423 1.423z" />
    </svg>
  )
}

function EyeIcon() {
  return (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M2.036 12.322a1.012 1.012 0 010-.639C3.423 7.51 7.36 4.5 12 4.5c4.638 0 8.573 3.007 9.963 7.178.07.207.07.431 0 .639C20.577 16.49 16.64 19.5 12 19.5c-4.638 0-8.573-3.007-9.963-7.178z" />
      <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
    </svg>
  )
}

function HandIcon() {
  return (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M10.05 4.575a1.575 1.575 0 10-3.15 0v3m3.15-3v-1.5a1.575 1.575 0 013.15 0v1.5m-3.15 0l.075 5.925m3.075-5.925v3m0-3a1.575 1.575 0 013.15 0v3m-3.15 0v3.75m-3.15-6.75v3.75m0 0h6.75m-6.75 0v3.75a2.25 2.25 0 002.25 2.25h3a2.25 2.25 0 002.25-2.25v-3.75m-7.5 0h7.5" />
    </svg>
  )
}

function CameraIcon() {
  return (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="m15.75 10.5 4.72-4.72a.75.75 0 0 1 1.28.53v11.38a.75.75 0 0 1-1.28.53l-4.72-4.72M4.5 18.75h9a2.25 2.25 0 0 0 2.25-2.25v-9a2.25 2.25 0 0 0-2.25-2.25h-9A2.25 2.25 0 0 0 2.25 7.5v9a2.25 2.25 0 0 0 2.25 2.25Z" />
    </svg>
  )
}

function TelegramIcon() {
  return (
    <svg className="w-6 h-6" fill="currentColor" viewBox="0 0 24 24">
      <path d="M11.944 0A12 12 0 0 0 0 12a12 12 0 0 0 12 12 12 12 0 0 0 12-12A12 12 0 0 0 12 0a12 12 0 0 0-.056 0zm4.962 7.224c.1-.002.321.023.465.14a.506.506 0 0 1 .171.325c.016.093.036.306.02.472-.18 1.898-.962 6.502-1.36 8.627-.168.9-.499 1.201-.82 1.23-.696.065-1.225-.46-1.9-.902-1.056-.693-1.653-1.124-2.678-1.8-1.185-.78-.417-1.21.258-1.91.177-.184 3.247-2.977 3.307-3.23.007-.032.014-.15-.056-.212s-.174-.041-.249-.024c-.106.024-1.793 1.14-5.061 3.345-.479.33-.913.49-1.302.48-.428-.008-1.252-.241-1.865-.44-.752-.245-1.349-.374-1.297-.789.027-.216.325-.437.893-.663 3.498-1.524 5.83-2.529 6.998-3.014 3.332-1.386 4.025-1.627 4.476-1.635z" />
    </svg>
  )
}

function ShieldIcon() {
  return (
    <svg className="w-6 h-6" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
      <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75m-3-7.036A11.959 11.959 0 013.598 6 11.99 11.99 0 003 9.749c0 5.592 3.824 10.29 9 11.623 5.176-1.332 9-6.03 9-11.622 0-1.31-.21-2.571-.598-3.751h-.152c-3.196 0-6.1-1.248-8.25-3.285z" />
    </svg>
  )
}
