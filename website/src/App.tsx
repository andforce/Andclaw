import { useState, useEffect } from 'react'
import Navbar from './components/Navbar'
import Hero from './components/Hero'
import Features from './components/Features'
import HowItWorks from './components/HowItWorks'
import Actions from './components/Actions'
import Comparison from './components/Comparison'
import InstallPage from './components/DeviceOwnerSetup'
import Footer from './components/Footer'

export default function App() {
  const [hash, setHash] = useState(window.location.hash)

  useEffect(() => {
    const onHashChange = () => setHash(window.location.hash)
    window.addEventListener('hashchange', onHashChange)
    return () => window.removeEventListener('hashchange', onHashChange)
  }, [])

  if (hash === '#/install') {
    return <InstallPage />
  }

  return (
    <div className="min-h-screen bg-dark-base bg-grid bg-circuit">
      <Navbar />
      <Hero />
      <Features />
      <HowItWorks />
      <Actions />
      <Comparison />
      <Footer />
    </div>
  )
}
