#!/usr/bin/env node
/**
 * Performance Budget Checker
 *
 * Enforces bundle size limits to prevent performance regression.
 * Run this as part of CI/CD to fail builds that exceed size budgets.
 *
 * Usage:
 *   npm run check-size        # After npm run build
 *   node scripts/check-bundle-size.js
 */

import { promises as fs } from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = fileURLToPath(new URL('.', import.meta.url));
const distDir = path.resolve(__dirname, '../receiver/static/dist');

// Performance budgets (in bytes)
const BUDGETS = {
    'dashboard.min.js': 40 * 1024,    // 40KB max
    'styles.min.css': 40 * 1024,      // 40KB max
};

// Warning threshold (80% of budget)
const WARNING_THRESHOLD = 0.8;

/**
 * Get file size in bytes
 */
async function getFileSize(filePath) {
    try {
        const stats = await fs.stat(filePath);
        return stats.size;
    } catch (error) {
        return null;
    }
}

/**
 * Format bytes to human-readable size
 */
function formatBytes(bytes) {
    if (bytes === 0) return '0 B';
    if (bytes < 1024) return `${bytes} B`;
    return `${(bytes / 1024).toFixed(2)} KB`;
}

/**
 * Get color for terminal output (ANSI codes)
 */
function colorize(text, color) {
    const colors = {
        red: '\x1b[31m',
        yellow: '\x1b[33m',
        green: '\x1b[32m',
        blue: '\x1b[36m',
        reset: '\x1b[0m'
    };
    return `${colors[color] || ''}${text}${colors.reset}`;
}

/**
 * Check if a file exceeds its budget
 */
async function checkFile(filename, budget) {
    const filePath = path.join(distDir, filename);
    const size = await getFileSize(filePath);

    if (size === null) {
        console.log(`  ${colorize('⚠', 'yellow')}  ${filename}: ${colorize('NOT FOUND', 'yellow')}`);
        return { status: 'missing', filename, size: 0, budget };
    }

    const percentage = (size / budget) * 100;
    const remaining = budget - size;
    const isOverBudget = size > budget;
    const isNearBudget = size > budget * WARNING_THRESHOLD && size <= budget;

    let status, symbol, color;
    if (isOverBudget) {
        status = 'fail';
        symbol = '✗';
        color = 'red';
    } else if (isNearBudget) {
        status = 'warn';
        symbol = '⚠';
        color = 'yellow';
    } else {
        status = 'pass';
        symbol = '✓';
        color = 'green';
    }

    const sizeStr = formatBytes(size);
    const budgetStr = formatBytes(budget);
    const remainingStr = remaining > 0 ? formatBytes(remaining) : formatBytes(-remaining);
    const percentageStr = `${percentage.toFixed(1)}%`;

    console.log(`  ${colorize(symbol, color)}  ${filename}`);
    console.log(`     Size:       ${colorize(sizeStr, color)} / ${budgetStr} (${colorize(percentageStr, color)})`);

    if (isOverBudget) {
        console.log(`     ${colorize('OVER BUDGET by ' + remainingStr, 'red')}`);
    } else if (isNearBudget) {
        console.log(`     ${colorize('Warning: ' + remainingStr + ' remaining', 'yellow')}`);
    } else {
        console.log(`     ${colorize(remainingStr + ' remaining', 'green')}`);
    }

    return { status, filename, size, budget, percentage };
}

/**
 * Main function
 */
async function main() {
    console.log('\n🎯 Performance Budget Check\n');
    console.log(`Checking bundles in: ${distDir}\n`);

    const results = [];

    for (const [filename, budget] of Object.entries(BUDGETS)) {
        const result = await checkFile(filename, budget);
        results.push(result);
        console.log('');
    }

    // Summary
    const failures = results.filter(r => r.status === 'fail');
    const warnings = results.filter(r => r.status === 'warn');
    const passes = results.filter(r => r.status === 'pass');
    const missing = results.filter(r => r.status === 'missing');

    console.log('─'.repeat(60));
    console.log('\n📊 Summary:\n');
    console.log(`  ${colorize('✓', 'green')} Passed:  ${passes.length}`);
    console.log(`  ${colorize('⚠', 'yellow')} Warnings: ${warnings.length}`);
    console.log(`  ${colorize('✗', 'red')} Failed:  ${failures.length}`);
    if (missing.length > 0) {
        console.log(`  ${colorize('⚠', 'yellow')} Missing: ${missing.length}`);
    }

    if (failures.length > 0) {
        console.log(`\n${colorize('❌ PERFORMANCE BUDGET EXCEEDED!', 'red')}`);
        console.log(`\nThe following files are over budget:\n`);
        failures.forEach(f => {
            const over = f.size - f.budget;
            console.log(`  • ${f.filename}: ${colorize(formatBytes(over), 'red')} over budget`);
        });
        console.log(`\n${colorize('Recommendations:', 'blue')}`);
        console.log(`  1. Review recent changes for unnecessary code`);
        console.log(`  2. Check for duplicate dependencies`);
        console.log(`  3. Ensure tree-shaking is working`);
        console.log(`  4. Consider code splitting for large features`);
        console.log(`  5. Review and optimize images/assets`);
        console.log('');
        process.exit(1);
    }

    if (warnings.length > 0) {
        console.log(`\n${colorize('⚠️  Warning: Some files are approaching budget limits', 'yellow')}`);
        console.log(`Consider optimizing before they exceed the budget.\n`);
    } else {
        console.log(`\n${colorize('✅ All bundles within budget!', 'green')}\n`);
    }

    if (missing.length > 0) {
        console.log(`${colorize('⚠️  Warning: Some expected files are missing', 'yellow')}`);
        console.log(`Did you forget to run 'npm run build'?\n`);
        process.exit(1);
    }

    process.exit(0);
}

main().catch(error => {
    console.error(colorize('\n❌ Error:', 'red'), error.message);
    process.exit(1);
});
