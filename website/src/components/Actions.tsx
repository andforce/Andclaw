import { useState } from 'react'
import { useInView } from '../hooks/useInView'

type Category = 'basic' | 'system' | 'media' | 'device'

const categories: { key: Category; label: string }[] = [
  { key: 'basic', label: '基础操作' },
  { key: 'system', label: '系统控制' },
  { key: 'media', label: '多媒体' },
  { key: 'device', label: '设备管理' },
]

const actions: Record<Category, { name: string; desc: string; code: string }[]> = {
  basic: [
    { name: 'click', desc: '在屏幕坐标模拟点击', code: '{"type":"click","x":540,"y":1200}' },
    { name: 'swipe', desc: '滑动手势，支持自定义时长', code: '{"type":"swipe","x":540,"y":1500,"end_x":540,"end_y":500}' },
    { name: 'long_press', desc: '长按屏幕坐标', code: '{"type":"long_press","x":300,"y":800,"duration":1000}' },
    { name: 'text_input', desc: '向焦点输入框注入文本', code: '{"type":"text_input","text":"Hello World"}' },
    { name: 'intent', desc: '启动应用、打开网页、拨号等', code: '{"type":"intent","action":"android.intent.action.VIEW","data":"https://..."}' },
    { name: 'wait', desc: '等待页面加载后重新检查', code: '{"type":"wait","duration":3000}' },
  ],
  system: [
    { name: 'back', desc: '模拟返回键', code: '{"type":"global_action","global_action":"back"}' },
    { name: 'home', desc: '回到桌面', code: '{"type":"global_action","global_action":"home"}' },
    { name: 'recents', desc: '打开最近任务', code: '{"type":"global_action","global_action":"recents"}' },
    { name: 'notifications', desc: '下拉通知栏', code: '{"type":"global_action","global_action":"notifications"}' },
    { name: 'volume', desc: '音量设置/调节/静音/查询', code: '{"type":"volume","volume_action":"set","extras":{"level":50}}' },
    { name: 'download', desc: '通过 URL 直接下载文件', code: '{"type":"download","data":"https://example.com/file.apk"}' },
  ],
  media: [
    { name: 'screenshot', desc: '截图并保存到相册', code: '{"type":"screenshot"}' },
    { name: 'take_photo', desc: '拍照并保存', code: '{"type":"camera","camera_action":"take_photo"}' },
    { name: 'start_video', desc: '开始录像', code: '{"type":"camera","camera_action":"start_video"}' },
    { name: 'stop_video', desc: '停止录像并保存', code: '{"type":"camera","camera_action":"stop_video"}' },
    { name: 'start_record', desc: '开始录屏', code: '{"type":"screen_record","screen_record_action":"start_record"}' },
    { name: 'stop_record', desc: '停止录屏并保存', code: '{"type":"screen_record","screen_record_action":"stop_record"}' },
  ],
  device: [
    { name: 'installPackage', desc: '静默安装 APK', code: '{"type":"dpm","dpm_action":"installPackage","extras":{...}}' },
    { name: 'uninstallPackage', desc: '静默卸载应用', code: '{"type":"dpm","dpm_action":"uninstallPackage","extras":{...}}' },
    { name: 'lockNow', desc: '立即锁屏', code: '{"type":"dpm","dpm_action":"lockNow"}' },
    { name: 'setApplicationHidden', desc: '隐藏/显示应用', code: '{"type":"dpm","dpm_action":"setApplicationHidden","extras":{...}}' },
    { name: 'setLockTaskPackages', desc: 'Kiosk 模式锁定应用', code: '{"type":"dpm","dpm_action":"setLockTaskPackages","extras":{...}}' },
    { name: 'setPermissionGrantState', desc: '自动授予应用权限', code: '{"type":"dpm","dpm_action":"setPermissionGrantState","extras":{...}}' },
  ],
}

const tabStyleMap: Record<Category, { active: string; badge: string; cardBorder: string }> = {
  basic: {
    active: 'bg-neon-cyan/15 text-neon-cyan border-neon-cyan/40',
    badge: 'bg-neon-cyan/10 text-neon-cyan',
    cardBorder: 'hover:border-neon-cyan/30',
  },
  system: {
    active: 'bg-neon-purple/15 text-neon-purple border-neon-purple/40',
    badge: 'bg-neon-purple/10 text-neon-purple',
    cardBorder: 'hover:border-neon-purple/30',
  },
  media: {
    active: 'bg-neon-green/15 text-neon-green border-neon-green/40',
    badge: 'bg-neon-green/10 text-neon-green',
    cardBorder: 'hover:border-neon-green/30',
  },
  device: {
    active: 'bg-neon-cyan/15 text-neon-cyan border-neon-cyan/40',
    badge: 'bg-neon-cyan/10 text-neon-cyan',
    cardBorder: 'hover:border-neon-cyan/30',
  },
}

export default function Actions() {
  const [active, setActive] = useState<Category>('basic')
  const { ref, isVisible } = useInView()
  const styles = tabStyleMap[active]

  return (
    <section id="actions" className="py-24 px-6 relative" ref={ref}>
      <div className="pointer-events-none absolute inset-0">
        <div className="absolute top-0 left-0 w-full h-px bg-gradient-to-r from-transparent via-neon-cyan/30 to-transparent" />
      </div>

      <div className="max-w-5xl mx-auto">
        <h2 className="font-[family-name:var(--font-family-display)] text-3xl md:text-4xl font-bold text-center mb-4">
          <span className="text-neon-green text-glow-cyan">操作</span>
          <span className="text-white">能力</span>
        </h2>
        <p className="text-gray-400 text-center mb-12 max-w-xl mx-auto">
          12+ 种操作类型，覆盖从基础手势到企业级设备管理的完整场景
        </p>

        {/* tabs */}
        <div className={`fade-in-up flex flex-wrap justify-center gap-2 mb-10 ${isVisible ? 'is-visible' : ''}`}>
          {categories.map((cat) => (
            <button
              key={cat.key}
              onClick={() => setActive(cat.key)}
              className={`px-5 py-2 rounded-lg text-sm font-medium transition-all border ${
                active === cat.key
                  ? tabStyleMap[cat.key].active
                  : 'text-gray-400 border-dark-border hover:text-gray-200 hover:border-gray-600'
              }`}
            >
              {cat.label}
            </button>
          ))}
        </div>

        {/* action cards */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
          {actions[active].map((action, i) => (
            <div
              key={action.name}
              className={`fade-in-up rounded-lg border border-dark-border bg-dark-card/40 p-4 transition-all ${styles.cardBorder} ${isVisible ? 'is-visible' : ''}`}
              style={{ transitionDelay: `${i * 60}ms` }}
            >
              <div className="flex items-start gap-3">
                <code className={`text-xs font-mono px-2 py-1 rounded shrink-0 mt-0.5 ${styles.badge}`}>
                  {action.name}
                </code>
                <p className="text-gray-300 text-sm">{action.desc}</p>
              </div>
              <pre className="mt-3 text-xs font-mono text-gray-500 bg-dark-base/60 rounded px-3 py-2 overflow-x-auto">
                {action.code}
              </pre>
            </div>
          ))}
        </div>
      </div>
    </section>
  )
}
