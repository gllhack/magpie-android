// Command gen-icons writes the Android launcher icons from magpie's own icon,
// so the app carries the same mark as the desktop one.
//
//	go run scripts/gen-icons/main.go upstream/internal/gui/icon-1024.png android/app/src/main/res
package main

import (
	"fmt"
	"image"
	"image/color"
	"image/draw"
	"image/png"
	"os"
	"path/filepath"
)

func main() {
	if len(os.Args) != 3 {
		fmt.Fprintln(os.Stderr, "usage: gen-icons <source.png> <res-dir>")
		os.Exit(2)
	}
	source, err := load(os.Args[1])
	if err != nil {
		fmt.Fprintln(os.Stderr, "gen-icons:", err)
		os.Exit(1)
	}
	res := os.Args[2]
	sizes := []struct {
		density string
		size    int
	}{
		{"mdpi", 48},
		{"hdpi", 72},
		{"xhdpi", 96},
		{"xxhdpi", 144},
		{"xxxhdpi", 192},
	}
	for _, s := range sizes {
		dir := filepath.Join(res, "mipmap-"+s.density)
		if err := os.MkdirAll(dir, 0o755); err != nil {
			fmt.Fprintln(os.Stderr, "gen-icons:", err)
			os.Exit(1)
		}
		path := filepath.Join(dir, "ic_launcher.png")
		if err := write(path, scale(source, s.size)); err != nil {
			fmt.Fprintln(os.Stderr, "gen-icons:", err)
			os.Exit(1)
		}
		fmt.Println("  " + path)
	}
}

func load(path string) (*image.NRGBA, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	img, err := png.Decode(f)
	if err != nil {
		return nil, err
	}
	b := img.Bounds()
	out := image.NewNRGBA(image.Rect(0, 0, b.Dx(), b.Dy()))
	draw.Draw(out, out.Bounds(), img, b.Min, draw.Src)
	return out, nil
}

func write(path string, img *image.NRGBA) error {
	f, err := os.Create(path)
	if err != nil {
		return err
	}
	defer f.Close()
	return png.Encode(f, img)
}

// scale resizes src to n×n: every destination pixel is the average of the
// source pixels under it, alphas included, which keeps the round edges of the
// icon clean at every size.
func scale(src *image.NRGBA, n int) *image.NRGBA {
	b := src.Bounds()
	w, h := b.Dx(), b.Dy()
	dst := image.NewNRGBA(image.Rect(0, 0, n, n))
	for y := 0; y < n; y++ {
		y0 := b.Min.Y + y*h/n
		y1 := b.Min.Y + (y+1)*h/n
		if y1 <= y0 {
			y1 = y0 + 1
		}
		for x := 0; x < n; x++ {
			x0 := b.Min.X + x*w/n
			x1 := b.Min.X + (x+1)*w/n
			if x1 <= x0 {
				x1 = x0 + 1
			}
			var sr, sg, sb, sa uint64
			var count uint64
			for yy := y0; yy < y1; yy++ {
				for xx := x0; xx < x1; xx++ {
					c := src.NRGBAAt(xx, yy)
					a := uint64(c.A)
					sr += uint64(c.R) * a
					sg += uint64(c.G) * a
					sb += uint64(c.B) * a
					sa += a
					count++
				}
			}
			if sa == 0 || count == 0 {
				continue
			}
			dst.SetNRGBA(x, y, color.NRGBA{
				R: uint8(sr / sa),
				G: uint8(sg / sa),
				B: uint8(sb / sa),
				A: uint8(sa / count),
			})
		}
	}
	return dst
}
