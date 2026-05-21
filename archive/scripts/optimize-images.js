import sharp from 'sharp';
import { promises as fs } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const inputDir = path.resolve(__dirname, '../receiver/static');

/**
 * Convert images to WebP format recursively
 */
async function convertToWebP(directory) {
    try {
        const entries = await fs.readdir(directory, { withFileTypes: true });

        for (const entry of entries) {
            const fullPath = path.join(directory, entry.name);

            if (entry.isDirectory() && entry.name !== 'node_modules' && entry.name !== 'dist') {
                await convertToWebP(fullPath);
            } else if (entry.isFile() && /\.(png|jpg|jpeg)$/i.test(entry.name)) {
                const outputPath = fullPath.replace(/\.\w+$/, '.webp');

                try {
                    const info = await sharp(fullPath)
                        .webp({ quality: 90, effort: 6 })
                        .toFile(outputPath);

                    const originalSize = (await fs.stat(fullPath)).size;
                    const reduction = ((originalSize - info.size) / originalSize * 100).toFixed(1);

                    console.log(`✓ ${entry.name} → ${path.basename(outputPath)}`);
                    console.log(`  ${(originalSize / 1024).toFixed(1)}KB → ${(info.size / 1024).toFixed(1)}KB (${reduction}% smaller)`);
                } catch (err) {
                    console.error(`✗ Failed to convert ${entry.name}:`, err.message);
                }
            }
        }
    } catch (err) {
        console.error(`Error processing directory ${directory}:`, err.message);
    }
}

console.log('🖼️  Converting images to WebP...\n');
convertToWebP(inputDir)
    .then(() => console.log('\n✓ WebP conversion complete!'))
    .catch(err => {
        console.error('Error:', err);
        process.exit(1);
    });
