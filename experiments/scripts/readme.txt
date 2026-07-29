These scripts assume the images will be at 3600x2700, if you want a different size then you need to make an all black image of the appropriate size.  Then replace the black_3600x2700.ppm with it. 

Copy a folder into the directory with the images in it.  It assumes the images are bmps.

make a tmp and output folder in the directory

copy a palette.ppm into the folder.  This palette file should include only the colors in the color model of the image.  It should also include the background or transparent color.

Then the trace.sh script needs to be updated so it includes the hex colors of the palette except for black.  Do not include the background or transparent color in this list of colors.

If the size of the images is not 3600x2700 you might also have to update the animate-output-template.svg file.  

To process the images run the process.sh script with the name of the directory as an argument.

