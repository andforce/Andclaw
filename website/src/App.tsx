import Navbar from './components/Navbar'
import Hero from './components/Hero'
import Features from './components/Features'
import HowItWorks from './components/HowItWorks'
import Actions from './components/Actions'
import Comparison from './components/Comparison'
import DeviceOwnerSetup from './components/DeviceOwnerSetup'
import Footer from './components/Footer'

export default function App() {
  return (
    <div className="min-h-screen bg-dark-base bg-grid bg-circuit">
      <Navbar />
      <Hero />
      <Features />
      <HowItWorks />
      <Actions />
      <Comparison />
      <DeviceOwnerSetup />
      <Footer />
    </div>
  )
}
