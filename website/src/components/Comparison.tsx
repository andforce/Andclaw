import { useInView } from '../hooks/useInView'

type CellValue = { ok: boolean; note?: string }

const solutions = [
  { name: 'Andclaw', highlight: true },
  { name: 'Open-AutoGLM', link: 'https://github.com/zai-org/Open-AutoGLM' },
  { name: '肉包 Roubao', link: 'https://github.com/Turbo1123/roubao' },
  { name: '豆包手机' },
]

const features: { label: string; values: CellValue[] }[] = [
  { label: '无需电脑',        values: [{ ok: true }, { ok: false, note: '需 PC 运行 Python' }, { ok: true }, { ok: true }] },
  { label: '无需专用硬件',    values: [{ ok: true }, { ok: true }, { ok: true }, { ok: false, note: '需购买 3499 元工程机' }] },
  { label: '无需 Shizuku / ADB', values: [{ ok: true, note: '无障碍服务' }, { ok: false, note: 'ADB 控制' }, { ok: false, note: '依赖 Shizuku' }, { ok: true }] },
  { label: '远程控制',        values: [{ ok: true, note: 'Telegram / 微信 ClawBot' }, { ok: false }, { ok: false }, { ok: false }] },
  { label: '自定义模型',      values: [{ ok: true, note: '多 Provider' }, { ok: true }, { ok: true }, { ok: false, note: '仅豆包' }] },
  { label: '开源',            values: [{ ok: true }, { ok: true }, { ok: true }, { ok: false }] },
  { label: '原生 Android',    values: [{ ok: true, note: 'Kotlin' }, { ok: false, note: 'Python' }, { ok: true, note: 'Kotlin' }, { ok: true }] },
]

function StatusCell({ value }: { value: CellValue }) {
  return (
    <div className="flex flex-col items-center gap-0.5">
      {value.ok
        ? <span className="text-neon-green font-bold text-lg">&#10003;</span>
        : <span className="text-gray-600 text-lg">&#10007;</span>}
      {value.note && (
        <span className={`text-[11px] leading-tight ${value.ok ? 'text-gray-400' : 'text-gray-500'}`}>
          {value.note}
        </span>
      )}
    </div>
  )
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
          与主流 AI 手机助手方案的全方位对比
        </p>

        <div className={`fade-in-up overflow-x-auto ${isVisible ? 'is-visible' : ''}`}>
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-dark-border">
                <th className="px-4 py-3 text-left text-gray-400 font-medium">特性</th>
                {solutions.map((s) => (
                  <th key={s.name} className={`px-4 py-3 text-center font-semibold ${s.highlight ? 'text-neon-cyan' : 'text-gray-300'}`}>
                    {'link' in s && s.link
                      ? <a href={s.link} target="_blank" rel="noopener noreferrer" className="hover:underline">{s.name}</a>
                      : s.name}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {features.map((feat) => (
                <tr key={feat.label} className="border-b border-dark-border/50 transition-colors hover:bg-dark-card/40">
                  <td className="px-4 py-4 text-gray-300 font-medium whitespace-nowrap">{feat.label}</td>
                  {feat.values.map((v, i) => (
                    <td key={i} className={`px-4 py-4 text-center ${solutions[i].highlight ? 'bg-neon-cyan/5' : ''}`}>
                      <StatusCell value={v} />
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        {/* advantages */}
        <div className={`fade-in-up mt-12 grid grid-cols-1 sm:grid-cols-2 gap-4 ${isVisible ? 'is-visible' : ''}`} style={{ transitionDelay: '200ms' }}>
          {[
            { title: '零外部依赖', desc: '基于 Android 无障碍服务，无需 Shizuku、ADB 或电脑' },
            { title: '远程控制', desc: '支持 Telegram Bot 与微信 ClawBot 双通道，适配不同远程控制场景' },
            { title: 'UI 层级 + 视觉双模感知', desc: '优先解析 Accessibility 节点树，WebView 场景自动切换截图分析' },
            { title: '循环检测 + 截图重试', desc: '同一动作重复 5 次自动截图视觉重试，避免 Agent 死循环' },
          ].map((item, i) => (
            <div key={i} className="flex items-start gap-3 text-sm">
              <span className="text-neon-green mt-0.5 shrink-0">&#10003;</span>
              <span className="text-gray-300">
                <span className="text-white font-medium">{item.title}</span>：{item.desc}
              </span>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
