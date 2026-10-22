// @vitest-environment node
/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'

const root = process.cwd() // vitest runs from frontend/
const html = readFileSync(join(root, 'index.html'), 'utf8')
const manifest = JSON.parse(readFileSync(join(root, 'public', 'manifest.webmanifest'), 'utf8'))

/** Width and height from a PNG's IHDR chunk, plus whether it has an alpha channel. */
function png(path: string) {
  const b = readFileSync(join(root, 'public', path))
  expect(b.subarray(1, 4).toString('latin1')).toBe('PNG')
  const colorType = b[25]
  return { width: b.readUInt32BE(16), height: b.readUInt32BE(20), alpha: colorType === 4 || colorType === 6 }
}

describe('home-screen app (opens full screen like a regular app)', () => {
  it('the manifest asks for standalone display with the right start page and colors', () => {
    expect(manifest.display).toBe('standalone')
    expect(manifest.start_url).toBe('/')
    expect(manifest.scope).toBe('/')
    expect(manifest.name).toBe('Personal Finance')
    expect(manifest.short_name.length).toBeLessThanOrEqual(12) // fits under a home-screen icon
    expect(manifest.background_color).toBe('#f3f5f4') // the prototype's --bg
  })

  it('every manifest icon exists at its stated size, including a maskable one', () => {
    for (const icon of manifest.icons) {
      const [w, h] = icon.sizes.split('x').map(Number)
      expect(png(icon.src.replace(/^\//, ''))).toMatchObject({ width: w, height: h })
    }
    expect(manifest.icons.some((i: { purpose: string }) => i.purpose === 'maskable')).toBe(true)
    expect(manifest.icons.some((i: { sizes: string }) => i.sizes === '512x512')).toBe(true)
  })

  it('index.html links the manifest and has the iPhone tags', () => {
    expect(html).toContain('<link rel="manifest" href="/manifest.webmanifest" />')
    expect(html).toContain('<link rel="apple-touch-icon" href="/icons/apple-touch-icon.png" />')
    expect(html).toContain('<meta name="apple-mobile-web-app-capable" content="yes" />')
    expect(html).toContain('<meta name="mobile-web-app-capable" content="yes" />')
    expect(html).toMatch(/name="viewport"[^>]*viewport-fit=cover/)
    // iPhone home-screen icons must be 180x180 with no transparency (iOS fills transparent areas with black).
    expect(png('icons/apple-touch-icon.png')).toEqual({ width: 180, height: 180, alpha: false })
  })
})
