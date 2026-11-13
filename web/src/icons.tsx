import type { SVGProps } from 'react'

import type { Kind } from './util'
export type { Kind }
type IconProps = SVGProps<SVGSVGElement>

const line = (p: IconProps): IconProps => ({
  width: 20, height: 20, viewBox: '0 0 24 24', fill: 'none', stroke: 'currentColor',
  strokeWidth: 1.75, strokeLinecap: 'round', strokeLinejoin: 'round', 'aria-hidden': true, ...p
})

export const SearchIcon = (p: IconProps) => <svg {...line(p)}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></svg>
export const GridIcon = (p: IconProps) => (
  <svg {...line(p)}><rect x="4" y="4" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="4" width="6.5" height="6.5" rx="1.5" />
    <rect x="4" y="13.5" width="6.5" height="6.5" rx="1.5" /><rect x="13.5" y="13.5" width="6.5" height="6.5" rx="1.5" /></svg>
)
export const ListIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 7h16M4 12h16M4 17h16" /></svg>
export const SortIcon = (p: IconProps) => <svg {...line(p)}><path d="M8 5v14m0 0-3-3m3 3 3-3M16 19V5m0 0-3 3m3-3 3 3" /></svg>
export const DownloadIcon = (p: IconProps) => <svg {...line(p)}><path d="M12 4v11m0 0-4-4m4 4 4-4M5 19h14" /></svg>
export const CloseIcon = (p: IconProps) => <svg {...line(p)}><path d="M6 6l12 12M18 6 6 18" /></svg>
export const ChevronRightIcon = (p: IconProps) => <svg {...line({ width: 16, height: 16, ...p })}><path d="m9 6 6 6-6 6" /></svg>
export const ArrowLeftIcon = (p: IconProps) => <svg {...line(p)}><path d="M19 12H5m0 0 6-6m-6 6 6 6" /></svg>
export const ArrowRightIcon = (p: IconProps) => <svg {...line(p)}><path d="M5 12h14m0 0-6-6m6 6-6 6" /></svg>
export const RefreshIcon = (p: IconProps) => <svg {...line(p)}><path d="M20 11a8 8 0 1 0-2.3 5.7M20 5v6h-6" /></svg>
export const GaugeIcon = (p: IconProps) => <svg {...line(p)}><path d="M4.5 17a8 8 0 1 1 15 0" /><path d="m12 14 4-4" /></svg>
export const ShieldIcon = (p: IconProps) => <svg {...line(p)}><path d="M12 3.5 5 6v5.5c0 4.2 2.8 7.6 7 9 4.2-1.4 7-4.8 7-9V6l-7-2.5Z" /><path d="m9 12 2.2 2.2L15.5 10" /></svg>

/** DropLink logo, redrawn as vectors. The gap and the link ring are real cut-outs, so it works on any background. */
export function Logo({ size = 28 }: { size?: number }) {
  const drop = 'M628 133 L336 494 A375 375 0 1 0 920 494 Z'
  return (
    <svg width={size * 0.77} height={size} viewBox="240 120 770 1000" role="img" aria-label="DropLink">
      <defs>
        <linearGradient id="dlL" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#0AE6CC" /><stop offset="1" stopColor="#00A9D0" /></linearGradient>
        <linearGradient id="dlR" x1="0" y1="0" x2="0" y2="1"><stop offset="0" stopColor="#00D4FF" /><stop offset="1" stopColor="#0A84FF" /></linearGradient>
        <linearGradient id="dlC" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="#3DCBE3" /><stop offset="1" stopColor="#0FA3D0" /></linearGradient>
        <clipPath id="dlLeft"><polygon points="959.5,117.5 310.5,1330.5 -400,1330.5 -400,117.5" /></clipPath>
        <mask id="dlCut" maskUnits="userSpaceOnUse" x="0" y="0" width="1254" height="1400">
          <rect width="1254" height="1400" fill="#fff" />
          <line x1="959.5" y1="117.5" x2="310.5" y2="1330.5" stroke="#000" strokeWidth="26" />
          <rect x="-200" y="-105" width="400" height="210" rx="105" transform="translate(628 750) rotate(-45)" fill="#000" />
        </mask>
      </defs>
      <g mask="url(#dlCut)">
        <path d={drop} fill="url(#dlR)" />
        <path d={drop} fill="url(#dlL)" clipPath="url(#dlLeft)" />
      </g>
      <rect x="-160" y="-65" width="320" height="130" rx="65" transform="translate(628 750) rotate(-45)" fill="url(#dlC)" />
    </svg>
  )
}

const DOC = 'M9 3h9.5L25 9.5V27a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2Z'
const FOLD = 'M18.5 3v5a1.5 1.5 0 0 0 1.5 1.5h5'

/** File type icons: one consistent set, colour-coded by kind. */
export function FileTypeIcon({ kind, size = 32 }: { kind: Kind; size?: number }) {
  const svg = { width: size, height: size, viewBox: '0 0 32 32', 'aria-hidden': true as const }
  switch (kind) {
    case 'folder':
      return (
        <svg {...svg}>
          <path d="M3 9a3 3 0 0 1 3-3h6.2a2 2 0 0 1 1.5.7L15.5 8H26a3 3 0 0 1 3 3v1H3V9Z" fill="#F2A900" />
          <path d="M3 12h26v11a3 3 0 0 1-3 3H6a3 3 0 0 1-3-3V12Z" fill="#FFC83D" />
        </svg>
      )
    case 'image':
      return (
        <svg {...svg}>
          <rect x="3" y="5" width="26" height="22" rx="5" fill="#2F9BFF" />
          <path d="M5 25l7-9 4.5 5.5 3-3.5L27 25Z" fill="#fff" fillOpacity=".95" />
          <circle cx="22" cy="12" r="2.6" fill="#fff" />
        </svg>
      )
    case 'video':
      return (
        <svg {...svg}>
          <rect x="3" y="5" width="26" height="22" rx="5" fill="#8B5CF6" />
          <path d="M13 11v10l8.5-5Z" fill="#fff" />
        </svg>
      )
    case 'audio':
      return (
        <svg {...svg}>
          <rect x="3" y="5" width="26" height="22" rx="5" fill="#EC4899" />
          <path d="M13 21V10.5l9-2V19" stroke="#fff" strokeWidth="2" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx="11" cy="21" r="2.6" fill="#fff" />
          <circle cx="20" cy="19" r="2.6" fill="#fff" />
        </svg>
      )
    case 'pdf':
      return (
        <svg {...svg}>
          <path d={DOC} fill="#E5484D" /><path d={FOLD} fill="#fff" fillOpacity=".4" />
          <text x="16" y="23" fontSize="7.2" fontWeight="700" fill="#fff" textAnchor="middle" fontFamily="system-ui, sans-serif">PDF</text>
        </svg>
      )
    case 'text':
      return (
        <svg {...svg}>
          <path d={DOC} fill="#64748B" /><path d={FOLD} fill="#fff" fillOpacity=".4" />
          <path d="M11 15h10M11 19h10M11 23h6" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
        </svg>
      )
    case 'archive':
      return (
        <svg {...svg}>
          <path d={DOC} fill="#F28C28" /><path d={FOLD} fill="#fff" fillOpacity=".4" />
          <path d="M14 11h2m-2 3h2m-2 3h2" stroke="#fff" strokeWidth="1.8" strokeLinecap="round" />
          <rect x="13" y="19.5" width="4" height="5" rx="1.2" fill="#fff" />
        </svg>
      )
    default:
      return (
        <svg {...svg}>
          <path d={DOC} fill="#8A94A6" /><path d={FOLD} fill="#fff" fillOpacity=".4" />
        </svg>
      )
  }
}

export const PlayIcon = (p: IconProps) => <svg {...line({ strokeWidth: 0, fill: 'currentColor', ...p })}><path d="M8 5.5v13a1 1 0 0 0 1.5.86l10.5-6.5a1 1 0 0 0 0-1.72L9.5 4.64A1 1 0 0 0 8 5.5Z" /></svg>
export const PauseIcon = (p: IconProps) => <svg {...line({ strokeWidth: 0, fill: 'currentColor', ...p })}><rect x="6.5" y="5" width="4" height="14" rx="1.2" /><rect x="13.5" y="5" width="4" height="14" rx="1.2" /></svg>
export const NextIcon = (p: IconProps) => <svg {...line(p)}><path d="M6 5.5v13l9-6.5-9-6.5ZM18.5 5v14" /></svg>
export const PrevIcon = (p: IconProps) => <svg {...line(p)}><path d="M18 5.5v13L9 12l9-6.5ZM5.5 5v14" /></svg>
export const ShuffleIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 7h3c5 0 5 10 10 10h3m0 0-2.5-2.5M20 17l-2.5 2.5M4 17h3c1.6 0 2.7-.8 3.6-1.9M20 7h-3c-1.6 0-2.7.8-3.6 1.9M20 7l-2.5-2.5M20 7l-2.5 2.5" /></svg>
export const RepeatIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 11V9a3 3 0 0 1 3-3h12m0 0-2.5-2.5M19 6l-2.5 2.5M20 13v2a3 3 0 0 1-3 3H5m0 0 2.5 2.5M5 18l2.5-2.5" /></svg>
export const VolumeIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 10v4h3.5L12 18V6L7.5 10H4ZM15.5 9a4 4 0 0 1 0 6M18 6.5a7.5 7.5 0 0 1 0 11" /></svg>
export const MuteIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 10v4h3.5L12 18V6L7.5 10H4ZM16 9.5l4 5m0-5-4 5" /></svg>
export const StarIcon = (p: IconProps) => <svg {...line(p)}><path d="m12 4 2.4 5 5.4.7-3.9 3.8.9 5.4L12 16.3l-4.8 2.6.9-5.4L4.2 9.700l5.4-.7L12 4Z" /></svg>
export const StarFillIcon = (p: IconProps) => <svg {...line({ fill: 'currentColor', ...p })}><path d="m12 4 2.4 5 5.4.7-3.9 3.8.9 5.4L12 16.3l-4.8 2.6.9-5.4L4.2 9.700l5.4-.7L12 4Z" /></svg>
export const HomeIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 11.5 12 4l8 7.5V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1v-7.500Z" /></svg>
export const ClockIcon = (p: IconProps) => <svg {...line(p)}><circle cx="12" cy="12" r="8" /><path d="M12 7.500V12l3 2" /></svg>
export const QueueIcon = (p: IconProps) => <svg {...line(p)}><path d="M4 7h12M4 12h12M4 17h7M18 14v6m0-6 3 1.500v4" /></svg>
export const ExpandIcon = (p: IconProps) => <svg {...line(p)}><path d="M5 10V5h5M19 14v5h-5M5 5l5.500 5.500M19 19l-5.500-5.500" /></svg>
export const MinimizeIcon = (p: IconProps) => <svg {...line(p)}><path d="m6 9 6 6 6-6" /></svg>
export const CheckIcon = (p: IconProps) => <svg {...line(p)}><path d="m5 12.500 4.500 4.500L19 7.500" /></svg>
export const PlusIcon = (p: IconProps) => <svg {...line(p)}><path d="M12 5v14M5 12h14" /></svg>
export const SettingsIcon = (p: IconProps) => <svg {...line(p)}><circle cx="12" cy="12" r="3" /><path d="M12 3v2.500M12 18.500V21M3 12h2.500M18.500 12H21M5.600 5.600l1.800 1.800M16.600 16.600l1.800 1.800M5.600 18.400l1.800-1.800M16.600 7.400l1.800-1.800" /></svg>
export const PipIcon = (p: IconProps) => <svg {...line(p)}><rect x="3.500" y="5" width="17" height="14" rx="2.500" /><rect x="12" y="12" width="6.500" height="4.500" rx="1" fill="currentColor" /></svg>
export const OfflineIcon = (p: IconProps) => <svg {...line(p)}><path d="M3 9a14 14 0 0 1 18 0M6 12.500a9 9 0 0 1 12 0M9.200 16a4.500 4.500 0 0 1 5.600 0M4 4l16 16" /></svg>
export const TrashIcon = (p: IconProps) => <svg {...line(p)}><path d="M5 7h14M10 7V5h4v2M7 7l.8 12h8.400L17 7" /></svg>
export const QrIcon = (p: IconProps) => <svg {...line(p)}><rect x="4" y="4" width="6" height="6" rx="1" /><rect x="14" y="4" width="6" height="6" rx="1" /><rect x="4" y="14" width="6" height="6" rx="1" /><path d="M14 14h3v3h-3zM20 14v.01M14 20h3m3-3v3" /></svg>
export const MoonIcon = (p: IconProps) => <svg {...line(p)}><path d="M20 14.500A8 8 0 0 1 9.500 4a8 8 0 1 0 10.500 10.500Z" /></svg>
export const KeyboardIcon = (p: IconProps) => <svg {...line(p)}><rect x="3" y="6" width="18" height="12" rx="2" /><path d="M7 10h.01M11 10h.01M15 10h.01M7 14h10" /></svg>
export const ZoomInIcon = (p: IconProps) => <svg {...line(p)}><circle cx="11" cy="11" r="6.500" /><path d="m20 20-4.200-4.200M11 8.500v5M8.500 11h5" /></svg>
export const ZoomOutIcon = (p: IconProps) => <svg {...line(p)}><circle cx="11" cy="11" r="6.500" /><path d="m20 20-4.200-4.200M8.500 11h5" /></svg>
export const LinkIcon = (p: IconProps) => <svg {...line(p)}><path d="M10 14a4 4 0 0 0 5.700 0l3-3a4 4 0 0 0-5.700-5.700l-1 1M14 10a4 4 0 0 0-5.700 0l-3 3a4 4 0 0 0 5.700 5.700l1-1" /></svg>
export const HelpIcon = (p: IconProps) => <svg {...line(p)}><circle cx="12" cy="12" r="8.500" /><path d="M9.700 9.500a2.400 2.400 0 1 1 3.400 2.200c-.7.400-1.100.900-1.100 1.800M12 17h.01" /></svg>
export const UploadIcon = (p: IconProps) => <svg {...line(p)}><path d="M12 16V5m0 0-4 4m4-4 4 4M5 19h14" /></svg>
