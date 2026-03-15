import { useInView } from '../hooks/useInView'

const rows = [
  { name: 'Andclaw', noRoot: true, noPC: true, standalone: true, ai: true, highlight: true },
  { name: 'Auto.js', noRoot: true, noPC: true, standalone: true, ai: false, highlight: false },
  { name: 'ADB + Python', noRoot: true, noPC: false, standalone: false, ai: 'optional' as const, highlight: false },
  { name: 'Frida + 脚本', noRoot: false, noPC: false, standalone: false, ai: false, highlight: false },
  { name: 'Appium', noRoot: true, noPC: false, standalone: false, ai: 'optional' as const, highlight: false },
  { name: 'UI Automator', noRoot: true, noPC: false, standalone: false, ai: false, highlight: false },
]

const headers = ['方案', '无需 Root', '无需电脑', '独立运行', 'AI 驱动']

function StatusCell({ value }: { value: boolean | 'optional' }) {
  if (value === true)
    return <span className="text-neon-green font-bold text-lg">&#10003;</span>
  if (value === 'optional')
    return <span className="text-yellow-400 text-xs font-medium">可选</span>
  return <span className="text-gray-600 text-lg">&#10007;</span>
}

export default function Comparison() {
  const { ref, isVisible } = useInView()

  return (
    <section id="comparison" className="py-24 px-6 relative" ref={ref}>
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute top-0 left-0 w-full h-px bg-gradient-to-r from-transparent via-neon-green/30 to-transparent" />
      </div>

      <div className="max-w-4xl mx-auto">
        <h2 className="font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold text-center mb-4">
          <span className="text-white">方案</span>
          <span className="text-neon-cyan text-glow-cyan">对比</span>
        </h2>
        <p className="text-gray-400 text-center mb-12 max-w-xl mx-auto">
          与主流 Android 自动化方案的全方位对比
        </p>

        <div className={`fade-in-up overflow-x-auto ${isVisible ? 'is-visible' : ''}`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-dark-border">
                {headers.map((h) => (
                  <th key={h} className="px-4 py-3 text-left text-gray-400 font-medium">
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <tr
                  key={row.name}
                  className={`border-b border-dark-border/50 transition-colors ${
                    row.highlight
                      ? 'bg-neon-cyan/5 hover:bg-neon-cyan/10'
                      : 'hover:bg-dark-card/40'
                  }`}
                >
                  <td className={`px-4 py-4 font-semibold ${row.highlight ? 'text-neon-cyan' : 'text-gray-300'}`}>
                    {row.name}
                  </td>
                  <td className="px-4 py-4 text-center"><StatusCell value={row.noRoot} /></td>
                  <td className="px-4 py-4 text-center"><StatusCell value={row.noPC} /></td>
                  <td className="px-4 py-4 text-center"><StatusCell value={row.standalone} /></td>
                  <td className="px-4 py-4 text-center"><StatusCell value={row.ai} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* advantages */}
        <div className={`fade-in-up mt-12 grid grid-cols-1 sm:grid-cols-2 gap-4 ${isVisible ? 'is-visible' : ''}`} style={{ transitionDelay: '200ms' }}>
          {[
            '完全在设备上运行，无需额外硬件',
            '大模型决策，自适应不同应用界面',
            '自然语言交互，无需编写脚本',
            '循环检测 + 截图重试，避免 Agent 死循环',
          ].map((text, i) => (
            <div key={i} className="flex items-start gap-3 text-sm">
              <span className="text-neon-green mt-0.5 shrink-0">&#10003;</span>
              <span className="text-gray-300">{text}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
