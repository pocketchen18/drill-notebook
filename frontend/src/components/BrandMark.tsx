/**
 * 品牌标识（侧栏 / 空状态用）。几何与 resources/icon/icon.svg 母版一致，内联以便随尺寸清晰渲染。
 * 渐变 id 带前缀，避免同页多个实例互相覆盖。
 */
export function BrandMark({ size = 30, className }: { size?: number; className?: string }): JSX.Element {
  return (
    <svg
      className={className ? `brand-mark-svg ${className}` : 'brand-mark-svg'}
      width={size}
      height={size}
      viewBox="0 0 512 512"
      role="img"
      aria-label="Drill Notebook"
      focusable="false"
    >
      <defs>
        <linearGradient id="brand-tile" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#5b7cff" />
          <stop offset="1" stopColor="#2440c4" />
        </linearGradient>
        <linearGradient id="brand-sheen" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stopColor="#ffffff" stopOpacity="0.22" />
          <stop offset="0.55" stopColor="#ffffff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect width="512" height="512" rx="116" fill="url(#brand-tile)" />
      <rect width="512" height="512" rx="116" fill="url(#brand-sheen)" />
      <path d="M178 108h122l64 64v202a30 30 0 0 1-30 30H178a30 30 0 0 1-30-30V138a30 30 0 0 1 30-30z" fill="#ffffff" />
      <path d="M300 108v34a30 30 0 0 0 30 30h34z" fill="#c9d6ff" />
      <rect x="196" y="206" width="120" height="20" rx="10" fill="#2f54eb" />
      <rect x="196" y="252" width="84" height="20" rx="10" fill="#2f54eb" fillOpacity="0.45" />
      <path d="M206 338l38 36 68-80" fill="none" stroke="#2f54eb" strokeWidth="30" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}
