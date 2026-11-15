import welcome from './aubree/welcome.webp'
import welcomeStill from './aubree/welcome-still.webp'
import wait from './aubree/wait.webp'
import waitStill from './aubree/wait-still.webp'
import celebrate from './aubree/celebrate.webp'
import celebrateStill from './aubree/celebrate-still.webp'
import sad from './aubree/sad.webp'
import sadStill from './aubree/sad-still.webp'
import offline from './aubree/offline.webp'
import offlineStill from './aubree/offline-still.webp'
import empty from './aubree/empty.webp'
import emptyStill from './aubree/empty-still.webp'
import sleepy from './aubree/sleepy.webp'
import sleepyStill from './aubree/sleepy-still.webp'

export type AubreeState = 'welcome' | 'wait' | 'celebrate' | 'sad' | 'offline' | 'empty' | 'sleepy'

// [animation, still picture]. The still is used when the person asks their system to reduce motion.
const art: Record<AubreeState, [string, string]> = {
  welcome: [welcome, welcomeStill],
  wait: [wait, waitStill],
  celebrate: [celebrate, celebrateStill],
  sad: [sad, sadStill],
  offline: [offline, offlineStill],
  empty: [empty, emptyStill],
  sleepy: [sleepy, sleepyStill]
}

/** Aubree, the DropLink mascot. Decorative: the words next to her always say the same thing. */
export function Aubree({ state, size = 120 }: { state: AubreeState; size?: number }) {
  const [moving, still] = art[state]
  return (
    <picture className="aubree" style={{ width: size, height: size }}>
      <source media="(prefers-reduced-motion: reduce)" srcSet={still} />
      <img src={moving} width={size} height={size} alt="" decoding="async" draggable={false} />
    </picture>
  )
}
