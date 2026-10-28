// Build-time extraction only. The Java runtime does not execute JavaScript.
const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const root = path.resolve(__dirname, '../..');
const checking = process.argv.includes('--check');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const write = (name, value) => {
    const target = path.join(root, name);
    if (checking) {
        if (!fs.existsSync(target) || fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n') !== value)
            throw new Error(`Generated resource differs: ${name}`);
        return;
    }
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
        ui_subtitle: 'From primordial life to a civilization', ui_close: 'Save before closing?',
        ui_save_missing: 'No saved game was found.', ui_language: 'Language',
        ui_start_civilization: 'Found a settlement', ui_start_civilization_desc: 'Begin gathering resources and researching technology.',
        ui_gather_stone: 'Gather stone', ui_gather_stone_desc: 'Gather %0 stone.',
        ui_ready_supported: '%0 are ready to found a settlement.', ui_civilization_actions: 'Settlement',
        ui_primitive: 'Primitive civilization', ui_jobs: 'Jobs', ui_job_workers: '%0: %1',
        ui_no_cost: 'No cost', ui_unlock_resource: 'Unlock the required resource first.',
        ui_no_workers: 'No available workers.', ui_assign_worker: 'Assign a worker', ui_release_worker: 'Release a worker',
        ui_job_workers_limited: '%0: %1 / %2', ui_shed_effect: 'Increases lumber and stone storage capacity by %0 each.',
        ui_bank_capacity: 'Increases money capacity by %0.', ui_banker_capacity: 'Adds %0 banker position.',
        ui_copper_mine: 'Mine', ui_miner_capacity: 'Adds %0 miner position.', ui_shed_copper: 'Increases copper storage capacity by %0.',
        ui_shed_iron: 'Increases iron storage capacity by %0.',
        ui_smelter_title: 'Smelters: %0', ui_smelter_wood: 'Wood fuel', ui_smelter_iron: 'Iron boost',
        ui_smelter_wood_cost: 'Each operating wood slot consumes %0 lumber per second.',
        ui_smelter_allocation_desc: 'Wood slots consume 3 lumber/s when fuel is available. Each operating iron slot adds 10% iron production. Unassigned fuel refills one iron slot per simulation step.',
        ui_increase_slots: 'Add a slot', ui_decrease_slots: 'Remove a slot', ui_no_slots: 'No available slots.',
        ui_cement_worker_capacity: 'Adds %0 cement worker positions.', ui_shed_cement: 'Increases cement storage capacity by %0.',
        ui_sawmill_effect: 'Increases lumber production by %0% and storage capacity by %1.',
        ui_farm_population: 'Increases population capacity by %0.',
        ui_craft_Plywood: 'Craft plywood', ui_craft_Brick: 'Craft brick', ui_craft_Wrought_Iron: 'Craft wrought iron',
        ui_craft_output: 'Produces %0 crafted material per operation.', ui_craftsman_capacity: 'Adds %0 craftsman position shared by all recipes.',
        job_craft_Plywood: 'Craftsman (plywood)', job_craft_Brick: 'Craftsman (brick)', job_craft_Wrought_Iron: 'Craftsman (wrought iron)',
        ui_garrison_capacity: 'Adds capacity for %0 automatically trained soldiers.',
        ui_garrison_hunting: 'Each soldier hunts 1/3 food and 0.1 furs per second, consumes 1 food per second and pays taxes.',
        ui_shed_furs: 'Increases fur storage capacity by %0.', ui_garrison_workers: 'Soldiers: %0 / %1',
        ui_garrison_progress: 'Training: %0% (+%1%/s)', ui_garrison_full: 'Barracks full',
        ui_thesis_professor: 'Libraries increase each professor\'s base knowledge output by 0.01.',
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
        ui_subtitle: '从原始生命走向文明', ui_close: '关闭前保存游戏？',
        ui_save_missing: '尚未找到存档。', ui_language: '语言',
        ui_start_civilization: '建立聚落', ui_start_civilization_desc: '开始采集资源与研究技术。',
        ui_gather_stone: '采集石头', ui_gather_stone_desc: '采集 %0 石头。',
        ui_ready_supported: '%0已准备好建立聚落。', ui_civilization_actions: '聚落',
        ui_primitive: '原始文明', ui_jobs: '工作', ui_job_workers: '%0：%1',
        ui_no_cost: '无消耗', ui_unlock_resource: '请先解锁所需资源。',
        ui_no_workers: '没有可调配的人口。', ui_assign_worker: '分配一名工人', ui_release_worker: '撤回一名工人',
        ui_job_workers_limited: '%0：%1 / %2', ui_shed_effect: '木材与石头的存储容量各增加 %0。',
        ui_bank_capacity: '资金容量增加 %0。', ui_banker_capacity: '增加 %0 个银行家岗位。',
        ui_copper_mine: '矿井', ui_miner_capacity: '增加 %0 个矿工岗位。', ui_shed_copper: '铜存储容量增加 %0。',
        ui_shed_iron: '铁存储容量增加 %0。',
        ui_smelter_title: '熔炉：%0', ui_smelter_wood: '木材燃料', ui_smelter_iron: '铁增产',
        ui_smelter_wood_cost: '每个运行中的木材燃料位消耗 %0 木材/秒。',
        ui_smelter_allocation_desc: '有燃料时，每个木材燃料位消耗 3 木材/秒；每个实际运行的铁产物位增加 10% 铁产出。未分配的燃料位每次生产结算自动补回一个铁产物位。',
        ui_increase_slots: '增加一个分配位', ui_decrease_slots: '减少一个分配位', ui_no_slots: '没有可用的分配位。',
        ui_cement_worker_capacity: '增加 %0 个水泥工岗位。', ui_shed_cement: '水泥存储容量增加 %0。',
        ui_sawmill_effect: '木材产出增加 %0%，存储容量增加 %1。',
        ui_farm_population: '人口容量增加 %0。',
        ui_craft_Plywood: '制造胶合板', ui_craft_Brick: '制造砖块', ui_craft_Wrought_Iron: '制造锻铁',
        ui_craft_output: '每次操作产出 %0 单位制造材料。', ui_craftsman_capacity: '增加 %0 个所有配方共用的工匠岗位。',
        job_craft_Plywood: '工匠（胶合板）', job_craft_Brick: '工匠（砖块）', job_craft_Wrought_Iron: '工匠（锻铁）',
        ui_garrison_capacity: '增加 %0 名自动训练士兵的容量。',
        ui_garrison_hunting: '每名士兵每秒狩猎 1/3 食物与 0.1 皮毛、消耗 1 食物并纳税。',
        ui_shed_furs: '皮毛存储容量增加 %0。', ui_garrison_workers: '士兵：%0 / %1',
        ui_garrison_progress: '训练：%0%（+%1%/秒）', ui_garrison_full: '驻军已满',
        ui_thesis_professor: '每座图书馆使每位教授的基础知识产出增加 0.01。',
    },
};
const defaults = JSON.parse(read('strings/strings.json'));
for (const [locale, file] of [['en_us', null], ['zh_cn', 'strings/strings.zh-CN.json']]) {
    const translated = file ? { ...defaults, ...JSON.parse(read(file)) } : defaults;
    const selected = Object.fromEntries(Object.entries(translated)
        .filter(([key, value]) => typeof value === 'string' &&
            /^(evo_|race_|genelab_genus_|resource_(RNA|DNA|Food|Lumber|Stone|Knowledge|Money|Copper|Iron|Cement|Furs|Plywood|Brick|Wrought_Iron)_name$|city_(food(?:_desc)?|lumber(?:_desc)?|basic_housing_(?:title|desc)|farm(?:_desc)?|lumber_yard|rock_quarry(?:_desc)?|shed_title1|university(?:_effect)?|max_knowledge|bank|smelter(?:_effect1)?|cement_plant|sawmill|foundry|crafted_mats|garrison|library(?:_effect)?)$|tech_(club|bone_tools|sundial|housing|agriculture|irrigation|currency|stone_axe|mining|storage|science|banking|investing|metal_working|copper_axes|copper_pickaxe|iron_mining|iron_axes|iron_pickaxe|smelting|cement|iron_saw|farm_house|reinforced_shed|foundry|artisans|garrison|library|thesis|research_grant|apprentices|carpentry)(?:_desc)?$|tech_(foundry|artisans|apprentices|carpentry)_effect$|job_(unemployed|farmer|lumberjack|quarry_worker|miner|professor|banker|cement_worker(?:_desc)?)$)/.test(key))
        .map(([key, value]) => [`evolve.${key}`, plain(value)]));
    for (const [key, value] of Object.entries(extras[locale])) selected[`evolve.${key}`] = value;
    // Minecraft uses 1-based indexed format placeholders; the upstream uses %0, %1, ... .
    const minecraft = Object.fromEntries(Object.entries(selected).map(([k, v]) =>
        [k, v.replace(/%([0-9]+)|%/g, (_, n) => n === undefined ? '%%' : `%${Number(n) + 1}$s`)]));
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
