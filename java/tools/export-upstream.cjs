// Build-time extraction only. The Java runtime does not execute JavaScript.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const resourceRoot = path.join(root, 'java/core/src/main/resources');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const write = (name, value) => {
    const target = path.join(root, name);
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, value, 'utf8');
};
const objectSource = (source, name) => {
    const start = source.indexOf(`export const ${name} = {`);
    if (start < 0) throw new Error(`Missing source object ${name}`);
    const end = source.indexOf('\n};', start);
    if (end < 0) throw new Error(`Missing end of ${name}`);
    return source.slice(start, end);
};
const blocks = source => {
    const starts = Array.from(source.matchAll(/^    (\w+): \{/gm));
    return starts.map((match, index) => [match[0], match[1],
        source.slice(match.index + match[0].length, starts[index + 1]?.index ?? source.length)]);
};
const traits = block => {
    const body = block.match(/\btraits: \{([\s\S]*?)\}/)?.[1] || '';
    return Array.from(body.matchAll(/(\w+):\s*([0-9.]+)/g), m => `${m[1]}:${m[2]}`).join(',');
};
const racesSource = read('src/races.js');
const actionsSource = read('src/actions.js');
const listBody = actionsSource.match(/const raceList = \[([\s\S]*?)\];/)[1].replace(/\/\/[^\n]*/g, '');
const raceOrder = Array.from(listBody.matchAll(/'([\w-]+)'/g), m => m[1])
    .filter(id => !['custom', 'hybrid'].includes(id));
const raceBlocks = new Map(blocks(objectSource(racesSource, 'races')).map(m => [m[1], m[2]]));
const speciesRows = ['# Generated from src/races.js and src/actions.js; MPL-2.0; do not edit.',
    `order=${raceOrder.join(',')}`];
for (const id of raceOrder) {
    const block = raceBlocks.get(id);
    const type = block?.match(/\btype: '([^']+)'/)?.[1];
    if (!type) throw new Error(`Cannot extract genus for ${id}`);
    speciesRows.push(`${id}.genus=${type}`, `${id}.traits=${traits(block)}`);
}
write('java/core/src/main/resources/evolve/species.properties', speciesRows.join('\n') + '\n');
const genusBlocks = blocks(objectSource(racesSource, 'genus_def'));
write('java/core/src/main/resources/evolve/genus.properties',
    '# Generated from src/races.js; MPL-2.0; do not edit.\n' +
    `order=${genusBlocks.map(m => m[1]).join(',')}\n` +
    genusBlocks.map(m => `${m[1]}.traits=${traits(m[2])}`).join('\n') + '\n');

function plain(text) {
    const entities = { nbsp: ' ', amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", times: '×',
        ndash: '–', mdash: '—', hellip: '…', percnt: '%' };
    return text.replace(/<(?:br\s*\/?|\/div|\/p|\/li)>/gi, '\n')
        .replace(/<[^>]*>/g, '')
        .replace(/&(#x[0-9a-f]+|#\d+|\w+);/gi, (all, entity) => {
            if (entity.startsWith('#x')) return String.fromCodePoint(parseInt(entity.slice(2), 16));
            if (entity.startsWith('#')) return String.fromCodePoint(Number(entity.slice(1)));
            return entities[entity] ?? all;
        })
        .replace(/\n{3,}/g, '\n\n').trim();
}
const extras = {
    en_us: {
        ui_title: 'Evolve', ui_resources: 'Resources', ui_actions: 'Evolution', ui_pause: 'Pause',
        ui_resume: 'Resume', ui_save: 'Save', ui_load: 'Load', ui_new: 'New game',
        ui_progress: 'Evolution: %0%', ui_ready: 'Evolution complete: %0 (%1). Civilization rules await migration.',
        ui_saved: 'Game saved.', ui_loaded: 'Game loaded.', ui_unaffordable: 'Insufficient resources.',
        ui_storage: 'Increase storage capacity first.', ui_full: 'Resource storage is full.',
        ui_paused: 'Game paused.', ui_error: 'Operation failed: %0', ui_confirm_new: 'Start a new game? Current progress will be replaced.',
        ui_confirm_load: 'Load the saved game and replace current progress?', ui_confirm_title: 'Confirm',
        ui_time: 'Elapsed: %0 s', ui_status: 'Ready', ui_cost: 'Cost', ui_capacity: 'Capacity',
        ui_count: 'Owned: %0', ui_description: 'Description', ui_effect: 'Effect',
    },
    zh_cn: {
        ui_title: '进化', ui_resources: '资源', ui_actions: '演化', ui_pause: '暂停',
        ui_resume: '继续', ui_save: '保存', ui_load: '读取', ui_new: '新游戏',
        ui_progress: '演化进度：%0%', ui_ready: '演化完成：%0（%1）。文明规则尚待迁移。',
        ui_saved: '游戏已保存。', ui_loaded: '游戏已读取。', ui_unaffordable: '资源不足。',
        ui_storage: '请先提升存储容量。', ui_full: '资源已达到存储上限。',
        ui_paused: '游戏已暂停。', ui_error: '操作失败：%0', ui_confirm_new: '开始新游戏？当前进度将被替换。',
        ui_confirm_load: '读取存档并替换当前进度？', ui_confirm_title: '确认',
        ui_time: '已运行：%0 秒', ui_status: '就绪', ui_cost: '消耗', ui_capacity: '容量',
        ui_count: '数量：%0', ui_description: '说明', ui_effect: '效果',
    },
};
const defaults = JSON.parse(read('strings/strings.json'));
for (const [locale, file] of [['en_us', null], ['zh_cn', 'strings/strings.zh-CN.json']]) {
    const translated = file ? { ...defaults, ...JSON.parse(read(file)) } : defaults;
    const selected = Object.fromEntries(Object.entries(translated)
        .filter(([key, value]) => typeof value === 'string' &&
            /^(evo_|race_|genelab_genus_|resource_(RNA|DNA)_name$)/.test(key))
        .map(([key, value]) => [`evolve.${key}`, plain(value)]));
    for (const [key, value] of Object.entries(extras[locale])) selected[`evolve.${key}`] = value;
    // Minecraft uses 1-based indexed format placeholders; the upstream uses %0, %1, ... .
    const minecraft = Object.fromEntries(Object.entries(selected).map(([k, v]) =>
        [k, v.replace(/%([0-9]+)/g, (_, n) => `%${Number(n) + 1}$s`)]));
    const escape = value => value.replace(/\\/g, '\\\\').replace(/\n/g, '\\n').replace(/\r/g, '\\r')
        .replace(/\t/g, '\\t');
    write(`java/core/src/main/resources/evolve/lang/${locale}.properties`,
        '# Adapted from Evolve strings; MPL-2.0; generated by tools/export-upstream.cjs\n' +
        Object.entries(selected).map(([k, v]) => `${k}=${escape(v)}`).join('\n') + '\n');
    write(`java/core/src/main/resources/assets/evolve/lang/${locale}.json`, JSON.stringify(minecraft, null, 2) + '\n');
}

const sourceFiles = fs.readdirSync(path.join(root, 'src')).filter(name => name.endsWith('.js'));
const modules = sourceFiles.map(name => {
    const source = read(`src/${name}`);
    return {
        file: `src/${name}`, lines: source.split('\n').length,
        browserReferences: (source.match(/\$\(|\bVue\b|\bdocument\b|\bwindow\b/g) || []).length,
        literalActionIds: Array.from(new Set(Array.from(source.matchAll(/\bid:\s*'([^']+)'/g), m => m[1]))),
    };
});
const manifest = {
    upstreamRevision: cp.execFileSync('git', ['log', '-1', '--format=%H', '--', 'src'], { cwd: root, encoding: 'utf8' }).trim(),
    packageVersion: JSON.parse(read('package.json')).version,
    scope: 'Static inventory only; presence of an ID does not mean its gameplay has been ported.',
    nativeRuntime: 'Java 21; no JavaScript engine or browser',
    speciesCount: raceOrder.length, modules,
};
write('java/UPSTREAM_INVENTORY.json', JSON.stringify(manifest, null, 2) + '\n');
process.stdout.write(`Exported ${raceOrder.length} species, ${genusBlocks.length} genera, two locales and source inventory.\n`);
