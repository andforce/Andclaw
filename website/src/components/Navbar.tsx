import { useEffect, useState } from 'react'

const links = [
  { href: '#features', label: '特性' },
  { href: '#how-it-works', label: '原理' },
  { href: '#actions', label: '能力' },
  { href: '#comparison', label: '对比' },
  { href: '#/install', label: '在线安装' },
]

export default function Navbar() {
  const [scrolled, setScrolled] = useState(false)

  useEffect(() => {
    const onScroll = () => setScrolled(window.scrollY > 60)
    window.addEventListener('scroll', onScroll, { passive: true })
    return () => window.removeEventListener('scroll', onScroll)
  }, [])

  return (
    <nav
      className={`fixed top-0 left-0 right-0 z-50 transition-all duration-300 ${
        scrolled
          ? 'bg-dark-base/80 backdrop-blur-md border-b border-dark-border/50'
          : 'bg-transparent'
      }`}
    >
      <div className="max-w-6xl mx-auto px-6 h-14 flex items-center justify-between">
        <a href="#" className="flex items-center gap-2">
          <img src="/icon.png" alt="Andclaw" className="w-7 h-7" />
          <span className="font-[family-name:var(--font-family-display)] text-sm font-bold">
            <span className="text-white">And</span>
            <span className="text-neon-cyan">claw</span>
          </span>
        </a>

        <div className="hidden sm:flex items-center gap-6">
          {links.map((l) => (
            <a
              key={l.href}
              href={l.href}
              className="text-sm text-gray-400 hover:text-neon-cyan transition-colors"
            >
              {l.label}
            </a>
          ))}
          <a
            href="https://github.com/andforce/Andclaw"
            target="_blank"
            rel="noopener noreferrer"
            className="text-sm px-4 py-1.5 rounded-md border border-neon-cyan/30 text-neon-cyan hover:bg-neon-cyan/10 transition-colors"
          >
            GitHub
          </a>
        </div>
      </div>
    </nav>
  )
}
