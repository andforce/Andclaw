export default function Hero() {
  return (
    <section className="relative min-h-screen flex flex-col items-center justify-center px-6 py-20 overflow-hidden">
      {/* decorative radial gradients */}
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute top-1/4 left-1/2 -translate-x-1/2 -translate-y-1/2 w-[600px] h-[600px] rounded-full bg-neon-purple/5 blur-[120px]" />
        <div className="absolute bottom-1/4 left-1/3 w-[400px] h-[400px] rounded-full bg-neon-cyan/5 blur-[100px]" />
      </div>

      <div className="relative z-10 flex flex-col items-center text-center max-w-3xl">
        {/* logo */}
        <img
          src="/icon.png"
          alt="Andclaw Logo"
          className="w-36 h-36 md:w-44 md:h-44 mb-8 animate-breathe"
        />

        {/* title */}
        <h1 className="font-[family-name:var(--font-family-display)] text-4xl md:text-6xl font-bold tracking-tight mb-6">
          <span className="text-white">And</span>
          <span className="text-neon-cyan text-glow-cyan">claw</span>
        </h1>

        {/* tagline */}
        <p className="text-xl md:text-2xl text-gray-300 mb-4 leading-relaxed">
          让 AI 像人类一样使用你的手机
        </p>

        {/* badges */}
        <div className="flex flex-wrap justify-center gap-3 mb-10">
          {['无需 Root', '无需电脑', '完全在设备上运行', '支持微信 ClawBot'].map((text) => (
            <span
              key={text}
              className="px-4 py-1.5 rounded-full text-sm font-medium border border-neon-purple/30 text-neon-purple bg-neon-purple/5"
            >
              {text}
            </span>
          ))}
        </div>

        <p className="text-sm md:text-base text-gray-400 mb-10 max-w-2xl leading-relaxed">
          支持本地直接操控，也支持通过 Telegram Bot 与微信 ClawBot 远程下发指令。
          其中 Telegram 可直接回传截图/录像文件，微信 ClawBot 当前以文本回执为主。
        </p>

        {/* CTA buttons */}
        <div className="flex flex-col sm:flex-row gap-4">
          <a
            href="https://github.com/andforce/Andclaw"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 px-8 py-3.5 rounded-lg font-semibold text-dark-base bg-neon-cyan hover:bg-neon-cyan/90 transition-all glow-cyan hover:scale-105"
          >
            <GithubIcon />
            GitHub
          </a>
          <a
            href="https://www.bilibili.com/video/BV1k8w4zeEL7"
            target="_blank"
            rel="noopener noreferrer"
            className="inline-flex items-center gap-2 px-8 py-3.5 rounded-lg font-semibold border border-neon-purple/50 text-neon-purple hover:bg-neon-purple/10 transition-all hover:scale-105"
          >
            <PlayIcon />
            观看演示
          </a>
        </div>
      </div>

      {/* scroll hint */}
      <div className="absolute bottom-8 left-1/2 -translate-x-1/2 animate-bounce">
        <svg className="w-6 h-6 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 14l-7 7m0 0l-7-7m7 7V3" />
        </svg>
      </div>
    </section>
  )
}

function GithubIcon() {
  return (
    <svg className="w-5 h-5" fill="currentColor" viewBox="0 0 24 24">
      <path d="M12 0C5.37 0 0 5.37 0 12c0 5.31 3.435 9.795 8.205 11.385.6.105.825-.255.825-.57 0-.285-.015-1.23-.015-2.235-3.015.555-3.795-.735-4.035-1.41-.135-.345-.72-1.41-1.23-1.695-.42-.225-1.02-.78-.015-.795.945-.015 1.62.87 1.845 1.23 1.08 1.815 2.805 1.305 3.495.99.105-.78.42-1.305.765-1.605-2.67-.3-5.46-1.335-5.46-5.925 0-1.305.465-2.385 1.23-3.225-.12-.3-.54-1.53.12-3.18 0 0 1.005-.315 3.3 1.23.96-.27 1.98-.405 3-.405s2.04.135 3 .405c2.295-1.56 3.3-1.23 3.3-1.23.66 1.65.24 2.88.12 3.18.765.84 1.23 1.905 1.23 3.225 0 4.605-2.805 5.625-5.475 5.925.435.375.81 1.095.81 2.22 0 1.605-.015 2.895-.015 3.3 0 .315.225.69.825.57A12.02 12.02 0 0024 12c0-6.63-5.37-12-12-12z" />
    </svg>
  )
}

function PlayIcon() {
  return (
    <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M14.752 11.168l-3.197-2.132A1 1 0 0010 9.87v4.263a1 1 0 001.555.832l3.197-2.132a1 1 0 000-1.664z" />
      <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M21 12a9 9 0 11-18 0 9 9 0 0118 0z" />
    </svg>
  )
}
