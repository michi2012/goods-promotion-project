import { Routes, Route } from 'react-router-dom'
import { OrderStatusPage } from '@/pages/OrderStatusPage'

function App() {
  return (
    <Routes>
      <Route path="/" element={<OrderStatusPage />} />
    </Routes>
  )
}

export default App
