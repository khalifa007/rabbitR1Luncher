import sys
from PIL import Image

def process_image(input_path, output_path):
    img = Image.open(input_path).convert("RGBA")
    data = img.getdata()
    
    new_data = []
    # Make white transparent
    for item in data:
        # If it's near white, make it transparent
        if item[0] > 240 and item[1] > 240 and item[2] > 240:
            new_data.append((255, 255, 255, 0))
        else:
            new_data.append(item)
            
    img.putdata(new_data)
    
    # Now let's just crop the top 25% to remove the "top line" (the back flap)
    width, height = img.size
    crop_top = int(height * 0.25)
    img_cropped = img.crop((0, crop_top, width, height))
    
    img_cropped.save(output_path, "PNG")
    print(f"Saved to {output_path}")

if __name__ == "__main__":
    process_image(sys.argv[1], sys.argv[2])
