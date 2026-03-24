import { useInView } from '../hooks/useInView'

const steps = [
  {
    num: '01',
    title: '下发指令',
    desc: '通过 App 界面、Telegram Bot 或微信 ClawBot 发送自然语言指令',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M7.5 8.25h9m-9 3H12m-9.75 1.51c0 1.6 1.123 2.994 2.707 3.227 1.129.166 2.27.293 3.423.379.35.026.67.21.865.501L12 21l2.755-4.133a1.14 1.14 0 01.865-.501 48.172 48.172 0 003.423-.379c1.584-.233 2.707-1.626 2.707-3.228V6.741c0-1.602-1.123-2.995-2.707-3.228A48.394 48.394 0 0012 3c-2.392 0-4.744.175-7.043.513C3.373 3.746 2.25 5.14 2.25 6.741v6.018z" />
      </svg>
    ),
  },
  {
    num: '02',
    title: '感知屏幕',
    desc: '无障碍服务捕获 UI 层次结构，WebView 场景自动截图',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M10.5 1.5H8.25A2.25 2.25 0 006 3.75v16.5a2.25 2.25 0 002.25 2.25h7.5A2.25 2.25 0 0018 20.25V3.75a2.25 2.25 0 00-2.25-2.25H13.5m-3 0V3h3V1.5m-3 0h3m-3 18.75h3" />
      </svg>
    ),
  },
  {
    num: '03',
    title: 'AI 决策',
    desc: '将屏幕数据 + 对话历史发送给 LLM，AI 返回 JSON 操作指令',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M9.813 15.904L9 18.75l-.813-2.846a4.5 4.5 0 00-3.09-3.09L2.25 12l2.846-.813a4.5 4.5 0 003.09-3.09L9 5.25l.813 2.846a4.5 4.5 0 003.09 3.09L15.75 12l-2.846.813a4.5 4.5 0 00-3.09 3.09z" />
      </svg>
    ),
  },
  {
    num: '04',
    title: '执行操作',
    desc: '解析指令并执行：点击、滑动、输入文字、启动应用等',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M15.042 21.672L13.684 16.6m0 0l-2.51 2.225.569-9.47 5.227 7.917-3.286-.672zM12 2.25V4.5m5.834.166l-1.591 1.591M20.25 10.5H18M7.757 14.743l-1.59 1.59M6 10.5H3.75m4.007-4.243l-1.59-1.59" />
      </svg>
    ),
  },
  {
    num: '05',
    title: '验证结果',
    desc: '等待 UI 刷新，重新捕获屏幕，检测循环并智能重试',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M9 12.75L11.25 15 15 9.75M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
      </svg>
    ),
  },
  {
    num: '06',
    title: '任务完成',
    desc: '目标达成后自动停止，或继续下一步直到完成整个任务',
    icon: (
      <svg className="w-7 h-7" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={1.5}>
        <path strokeLinecap="round" strokeLinejoin="round" d="M3 3v1.5M3 21v-6m0 0l2.77-.693a9 9 0 016.208.682l.108.054a9 9 0 006.086.71l3.114-.732a48.524 48.524 0 01-.005-10.499l-3.11.732a9 9 0 01-6.085-.711l-.108-.054a9 9 0 00-6.208-.682L3 4.5M3 15V4.5" />
      </svg>
    ),
  },
]

export default function HowItWorks() {
  const { ref, isVisible } = useInView()

  return (
    <section id="how-it-works" className="py-24 px-6 relative" ref={ref}>
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute top-0 left-0 w-full h-px bg-gradient-to-r from-transparent via-neon-purple/30 to-transparent" />
      </div>

      <div className="max-w-5xl mx-auto">
        <h2 className="font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold text-center mb-4">
          <span className="text-white">工作</span>
          <span className="text-neon-purple text-glow-purple">原理</span>
        </h2>
        <p className="text-gray-400 text-center mb-16 max-w-xl mx-auto">
          AI Agent 自主循环：感知 → 决策 → 执行 → 验证，直到任务完成
        </p>

        <div className="relative">
          {/* connecting line */}
          <div className="hidden md:block absolute left-1/2 top-0 bottom-0 w-px bg-gradient-to-b from-neon-cyan/30 via-neon-purple/30 to-neon-green/30" />

          <div className="space-y-8 md:space-y-0">
            {steps.map((step, i) => {
              const isLeft = i % 2 === 0
              return (
                <div
                  key={step.num}
                  className={`fade-in-up relative md:flex items-center ${isLeft ? '' : 'md:flex-row-reverse'} ${isVisible ? 'is-visible' : ''}`}
                  style={{ transitionDelay: `${i * 120}ms` }}
                >
                  {/* content */}
                  <div className={`md:w-[calc(50%-2rem)] ${isLeft ? 'md:text-right md:pr-8' : 'md:text-left md:pl-8'}`}>
                    <div className="inline-block rounded-xl border border-dark-border bg-dark-card/60 backdrop-blur-sm p-5 transition-all hover:border-neon-cyan/30">
                      <div className="flex items-center gap-3 mb-2">
                        <span className="text-neon-cyan font-[family-name:var(--font-family-display)] text-sm font-bold">{step.num}</span>
                        <h3 className="text-white font-semibold">{step.title}</h3>
                      </div>
                      <p className="text-gray-400 text-sm">{step.desc}</p>
                    </div>
                  </div>

                  {/* center node */}
                  <div className="hidden md:flex absolute left-1/2 -translate-x-1/2 w-10 h-10 rounded-full bg-dark-base border-2 border-neon-cyan/40 items-center justify-center text-neon-cyan z-10">
                    {step.icon}
                  </div>

                  {/* spacer */}
                  <div className="hidden md:block md:w-[calc(50%-2rem)]" />
                </div>
              )
            })}
          </div>

          {/* loop arrow */}
          <div className={`fade-in-up mt-8 flex justify-center ${isVisible ? 'is-visible' : ''}`} style={{ transitionDelay: '750ms' }}>
            <div className="flex items-center gap-2 px-5 py-2.5 rounded-full border border-neon-green/30 bg-neon-green/5 text-neon-green text-sm font-medium">
              <svg className="w-4 h-4" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                <path strokeLinecap="round" strokeLinejoin="round" d="M16.023 9.348h4.992v-.001M2.985 19.644v-4.992m0 0h4.992m-4.993 0l3.181 3.183a8.25 8.25 0 0013.803-3.7M4.031 9.865a8.25 8.25 0 0113.803-3.7l3.181 3.182" />
              </svg>
              循环执行，直到任务完成
            </div>
          </div>
        </div>
      </div>
    </section>
  )
}
