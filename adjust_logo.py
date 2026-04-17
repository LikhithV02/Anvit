import os
import sys
from PIL import Image, ImageChops

def autocrop(image):
    if image.mode == 'RGBA':
        alpha = image.split()[-1]
        bbox = alpha.getbbox()
        return image.crop(bbox) if bbox else image
    return image

def resize_and_pad(img, canvas_size, inner_size):
    img_ratio = img.width / img.height
    inner_ratio = inner_size[0] / inner_size[1]
    
    if img_ratio > inner_ratio:
        new_w = inner_size[0]
        new_h = int(new_w / img_ratio)
    else:
        new_h = inner_size[1]
        new_w = int(new_h * img_ratio)
        
    img_resized = img.resize((new_w, new_h), Image.Resampling.LANCZOS)
    
    new_img = Image.new('RGBA', canvas_size, (0, 0, 0, 0))
    offset = ((canvas_size[0] - new_w) // 2, (canvas_size[1] - new_h) // 2)
    new_img.paste(img_resized, offset)
    return new_img

def main():
    # --- ADJUST ZOOM HERE ---
    # 66-72  = Standard (safe zone)
    # 85-100 = Large
    # 108    = Full Bleed (square edges)
    # 120+   = Zoomed in (overflows edges)
    ZOOM_LEVEL = 180
    # -----------------------

    img_path = 'Anvit Gemini logo.png'
    if not os.path.exists(img_path):
        print(f"Error: '{img_path}' not found in the current directory.")
        return

    try:
        img = autocrop(Image.open(img_path).convert("RGBA"))
    except Exception as e:
        print(f"Error opening image: {e}")
        return

    densities = {
        'mdpi': 1,
        'hdpi': 1.5,
        'xhdpi': 2.0,
        'xxhdpi': 3.0,
        'xxxhdpi': 4.0
    }
    
    base_dir = 'app/src/main/res'
    
    for dpi, scale in densities.items():
        mipmap_dir = os.path.join(base_dir, f'mipmap-{dpi}')
        os.makedirs(mipmap_dir, exist_ok=True)
        
        # Adaptive Icon (Foreground)
        canvas_size = int(108 * scale)
        inner_size = int(ZOOM_LEVEL * scale)
        resize_and_pad(img, (canvas_size, canvas_size), (inner_size, inner_size)).save(os.path.join(mipmap_dir, 'ic_launcher_foreground.png'))
        
        # Legacy Icons
        legacy_canvas = int(48 * scale)
        legacy_inner = int((ZOOM_LEVEL / 108 * 48) * scale)
        legacy_res = resize_and_pad(img, (legacy_canvas, legacy_canvas), (legacy_inner, legacy_inner))
        legacy_res.save(os.path.join(mipmap_dir, 'ic_launcher.png'))
        legacy_res.save(os.path.join(mipmap_dir, 'ic_launcher_round.png'))

    print(f"Success! Icons updated in '{base_dir}' with zoom level: {ZOOM_LEVEL}")

if __name__ == '__main__':
    main()
