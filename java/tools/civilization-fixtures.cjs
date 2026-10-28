// Development-only execution of ORIGINAL primitive rules. No JS is shipped with the Java core.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8').replace(/\r\n/g, '\n');
const files = ['src/actions.js', 'src/tech.js', 'src/main.js', 'src/functions.js', 'src/resources.js', 'src/races.js', 'src/vars.js', 'src/space.js', 'src/jobs.js'];
const sources = Object.fromEntries(files.map(file => [file, read(file)]));
function fn(file, name) {
    const source = sources[`src/${file}.js`];
    const start = source.indexOf(`function ${name}(`);
    const end = source.indexOf('\n}', start) + 2;
    if (start < 0 || end < 2) throw new Error(`Missing ${file}.${name}`);
    return source.slice(start, end);
}
function block(file, id, indent) {
    const source = sources[`src/${file}.js`];
    const start = source.indexOf(`${' '.repeat(indent)}${id}: {`);
    const match = new RegExp(`^ {${indent}}\\w+:`, 'gm');
    match.lastIndex = start + indent + id.length + 3;
    const end = match.exec(source)?.index;
    if (start < 0 || !end) throw new Error(`Missing block ${file}.${id}`);
    return source.slice(start, end);
}
function section(start, end) {
    const source = sources['src/main.js'];
    const a = source.indexOf(start);
    const b = source.indexOf(end, a);
    if (a < 0 || b < 0) throw new Error(`Missing main section ${start}`);
    return source.slice(a, b);
}
const resourceIds = ['RNA', 'DNA', 'Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper', 'human'];
const traitIds = ['adaptable', 'wasteful', 'creative', 'diverse'];
const structureIds = ['basic_housing', 'farm', 'lumber_yard', 'rock_quarry', 'shed', 'university', 'bank', 'mine'];
const cityIds = ['food', 'lumber', 'stone', ...structureIds];
const techIds = ['club', 'bone_tools', 'sundial', 'housing', 'agriculture', 'irrigation', 'currency', 'stone_axe', 'mining', 'storage', 'science', 'banking', 'investing', 'metal_working', 'copper_axes', 'copper_pickaxe'];
const jobIds = ['farmer', 'lumberjack', 'quarry_worker', 'miner', 'professor', 'banker'];
const fresh = { biome: 'grassland', universe: 'standard', seeded: false, rapidMutation: false,
    massExtinction: 0, novice: 0, master: 0, grandmaster: 0, unbound: 0, obsolete: 0,
    nightmare: false, extinctSpecies: [], productionMultiplier: 1 };
const adjustmentNames = [...fn('functions', 'adjustCosts').matchAll(/costs = (\w+)\(/g)].map(match => match[1]);
const adjustments = ['adjustCosts', ...adjustmentNames, 'craftAdjust'].map(name => fn('functions', name)).join('\n');
function environment(primary = true, multiplier = 1) {
    const g = { race: { species: 'human', universe: 'standard' },
        resource: { RNA: { amount: 300, max: 500, display: true, delta: 0 }, DNA: { amount: 300, max: 500, display: true, delta: 0 } },
        tech: { evo: 7, evo_humanoid: primary ? 2 : 1 }, evolution: {}, city: { biome: 'grassland', ptrait: [], geology: {}, calendar: { day: 0 } },
        civic: { govern: {}, foreign: { gov0: {}, gov1: {}, gov2: {} }, garrison: { workers: 0 }, homeless: 0, taxes: { tax_rate: 20 } },
        stats: { achieve: {}, feat: {}, starved: 0, cfood: 0, tfood: 0, clumber: 0, tlumber: 0, cstone: 0, tstone: 0, know: 0 },
        genes: { minor: {} }, blood: {}, prestige: {}, portal: {}, space: {}, interstellar: {}, galaxy: {}, tauceti: {}, arpa: {}, eden: {},
        settings: { boring: true, mtorder: [], tabLoad: false, pause: false }, queue: {}, seed: 2 };
    const temporary = { resource: {} };
    let names = 0;
    let loops = 0;
    let capacitiesInitialized = false;
    const sandbox = { global: g, tmp_vars: temporary, p_on: {}, support_on: {}, breakdown: { p: { consume: {} }, c: {} },
        loc: key => key, $: value => [value], deepClone: structuredClone,
        genus_def: { humanoid: { traits: { adaptable: 1, wasteful: 1 } } },
        races: { human: { type: 'humanoid', traits: { creative: 1, diverse: 1 } } },
        traits: {}, planetTraits: {}, biomes: { grassland: { vars: () => [1.2] } }, govActive: () => 0, fathomCheck: () => 0,
        BHStorageMulti: n => n, spatialReasoning: n => n, getShrineBonus: () => ({ mult: 1 }), structName: id => id,
        storageMultipler: () => 1, traitCostMod: (trait, cost) => cost, isStargateOn: () => false, shrineBonusActive: () => false,
        powerOnNewStruct: () => {},
        checkAltPurgatory: (region, id, alternate, value) => { g[region][id] = structuredClone(value); },
        eventActive: () => ({ active: false }), getHalloween: () => ({ active: false }),
        clearElement: () => {}, registerTech: () => {}, addAction: () => {},
        genCivName: () => ({ s0: names++, s1: 'fixture' }),
        messageQueue: () => {}, flib: () => 'human', calcPillar: () => {}, commisionGarrison: () => {},
        defineGovernment: () => {}, calc_mastery: () => {}, altRace: () => {}, tagEvent: () => {}, alevel: () => 1,
        loadTab: () => {}, loadSpecialResource: () => {}, blubberFill: () => {},
        loadResource: (id, wiki, max) => { g.resource[id] ??= { amount: 0, max, display: false, delta: 0 }; },
        loadJob: (id, define, impact) => { g.civic[id] = { workers: 0, assigned: 0, display: false, impact }; },
        workerScale: n => n, highPopAdjust: n => n, jobName: id => id, racialTrait: () => 1,
        syndicate: () => 1, production: () => 1, astroSign: '', gene_sequence: false,
    };
    const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    const city = cityIds.map(id => block('actions', id, 8)).join('\n');
    const tech = techIds.map(id => block('tech', id, 4)).join('\n');
    const functions = [fn('vars', 'seededRandom'), fn('functions', 'costMultiplier'), fn('functions', 'modRes'),
        fn('actions', 'payCosts'), fn('actions', 'checkCosts'), fn('actions', 'initStruct'), fn('actions', 'bank_vault'), fn('actions', 'dirt_adjust'), fn('space', 'incrementStruct'),
        fn('races', 'setTraitRank'), fn('races', 'combineTraits'), fn('resources', 'defineResources'),
        fn('jobs', 'defineJobs'), fn('jobs', 'farmerValue'), fn('jobs', 'jobScale'), fn('actions', 'sentience'), adjustments].join('\n');
    const jobSource = fn('jobs', 'loadJob');
    const jobMethods = jobSource.slice(jobSource.lastIndexOf('                add(){'), jobSource.indexOf('                level(job){'));
    const midSource = fn('main', 'midLoop');
    const midBase = midSource.slice(midSource.indexOf('        var caps = {', midSource.indexOf('        // Resource caps')), midSource.indexOf('        var lCaps ='));
    const midStorage = midSource.slice(midSource.indexOf("        if (global.city['rock_quarry']){"), midSource.indexOf("        else if (global.city['graveyard']){"));
    const shedStorage = section("        if (global.city['shed']){", "        if (global.race['lone_survivor']){");
    const universityCaps = section("        if (global.city['university']){", "        if (global.race['lone_survivor'] && global.tauceti['alien_outpost']){");
    const bankCaps = section("        if (global.city['bank'] || (global.race['cataclysm'] && p_on['spaceport'])){", "        if (global.eden['eternal_bank']){\n            let vault");
    const refillSource = section("        Object.keys(job_desc).forEach(function (job){", '        let entertainment = 0;');
    const program = `${functions}\nvar actions = { city: {${city}}, tech: {${tech}},
        space: { spc_red: { biodome: { title: () => 'biodome' } } } };
        Math.random = () => seededRandom(0, 1);
        ${sources['src/vars.js'].slice(sources['src/vars.js'].indexOf('Math.rand ='), sources['src/vars.js'].indexOf("global['seed']"))}
        function fast() {
            const global_multiplier = ${multiplier}; const time_multiplier = .25;
            const date = new Date('2026-01-01T00:00:00Z'); const q_multiplier = 1; const zigVal = 1;
            const job_desc = ${JSON.stringify(Object.fromEntries(jobIds.map(id => [id, 'fixture'])))};
            ${refillSource}
            ${section('        // Consumption\n', '        // Fortress Repair\n')}
            ${section('        // Citizen Growth\n', "        if (global.space['shipyard']")}
            let hunger = fed ? 1 : .5;
            ${section('        // Knowledge\n', '        // Omniscience\n')}
            ${section('        // Lumber\n', "        let refinery = global.city['metal_refinery']")}
            ${section('        // Stone / Amber\n', "        // Crystal\n")}
            const shrineMetal = { mult: 1 }; const refinery = 0;
            ${section('        // Miners\n', '        // Mars Mining\n')}
            const FactoryMoney = 0;
            ${section('        // Income\n', "        if (global.tech['gambling']")}
        }
        function mid() {
            ${midBase}
            ${midStorage}
            ${shedStorage}
            const lCaps = { professor: 0 };
            ${universityCaps}
            ${bankCaps}
            for (const id of ['Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper']) global.resource[id].max = caps[id];
            global.civic.professor.max = lCaps.professor;
            global.civic.banker.max = jobScale(global.city.bank?.count || 0);
            global.civic.miner.max = jobScale(global.city.mine?.count || 0);
        }
        function assign(job, delta) {
            const keyMultiplier = () => Math.abs(delta);
            const controls = { ${jobMethods} };
            controls[delta > 0 ? 'add' : 'sub']();
        }
        sentience();`;
    vm.runInContext(program, context, { timeout: 1000 });
    for (const id of ['unemployed', ...jobIds]) g.civic[id].max = ['miner', 'professor', 'banker'].includes(id) ? 0 : -1;
    for (const id of Object.keys(g.resource)) {
        temporary.resource[id] = { temp_max: g.resource[id].max };
        sandbox.breakdown.p[id] = {};
        sandbox.breakdown.p.consume[id] = {};
        sandbox.breakdown.c[id] = {};
    }
    // Atomic host invariant: release unemployed first, then jobs in reverse order after deaths.
    function normalizeWorkers() {
        let excess = g.civic.unemployed.workers + jobIds.reduce((sum, id) => sum + g.civic[id].workers, 0) - g.resource.human.amount;
        for (const id of ['unemployed', ...jobIds.toReversed()]) {
            const loss = Math.min(g.civic[id].workers, Math.max(0, excess));
            g.civic[id].workers -= loss;
            excess -= loss;
        }
    }
    const visible = rule => Object.entries(rule.reqs || {}).every(([id, rank]) => (g.tech[id] || 0) >= rank) &&
        (!rule.grant || (g.tech[rule.grant[0]] || 0) < rule.grant[1]) && (!rule.condition || rule.condition());
    function act(id, repetitions = 1) {
        const [category, name] = id.split('-');
        const rule = sandbox.actions[category][name];
        for (let i = 0; i < repetitions; i++) {
            if (!visible(rule)) break;
            const succeeded = rule.action.call(rule, {});
            if (succeeded && rule.grant) g.tech[rule.grant[0]] = rule.grant[1];
        }
    }
    function fast(count) {
        for (let i = 0; i < count; i++) {
            for (const id of resourceIds) temporary.resource[id].temp_max = g.resource[id].max + g.resource[id].amount;
            vm.runInContext('fast()', context, { timeout: 1000 });
            normalizeWorkers();
            for (const id of resourceIds) g.resource[id].amount = Math.min(g.resource[id].max, g.resource[id].amount);
            if (++loops % 4 === 0) { vm.runInContext('mid()', context, { timeout: 1000 }); capacitiesInitialized = true; }
        }
    }
    function view() {
        return {
            resources: Object.fromEntries(resourceIds.map(id => [id, { amount: g.resource[id].amount, capacity: g.resource[id].max,
                visible: g.resource[id].display, changePerSecond: 0 }])),
            structures: Object.fromEntries(Object.entries(g.city).filter(([id]) => structureIds.includes(id)).map(([id, v]) => [id, v.count])),
            technologies: { ...g.tech }, traits: Object.fromEntries(traitIds.map(id => [id, g.race[id]])), seed: g.seed,
            unemployed: g.civic.unemployed.workers, workers: Object.fromEntries(jobIds.map(id => [id, g.civic[id].workers])),
            assigned: Object.fromEntries(jobIds.map(id => [id, g.civic[id].assigned])),
            capacitiesInitialized,
            available: Object.entries({ ...Object.fromEntries(cityIds.map(id => [`city-${id}`, sandbox.actions.city[id]])),
                ...Object.fromEntries(techIds.map(id => [`tech-${id}`, sandbox.actions.tech[id]])) }).filter(([, rule]) => visible(rule))
                .map(([id, rule]) => ({ id, costs: Object.fromEntries(Object.entries(sandbox.adjustCosts(rule)).map(([res, cost]) => [res, cost()]).filter(([, cost]) => cost > 0)) })),
        };
    }
    function save() {
        const data = view();
        return { schemaVersion: 6, ruleset: 'evolve-1.4.10/copper-1', elapsedTicks: 0, revision: 0, tickRemainder: 0, loopCycle: 0,
            paused: false, phase: 'PRIMITIVE', randomSeed: g.seed, species: 'human', genus: 'humanoid', progress: 100,
            context: { ...fresh, productionMultiplier: multiplier }, resources: data.resources, structures: data.structures,
            technologies: data.technologies, traits: data.traits, civilization: { primaryGenus: primary ? 'humanoid' : '',
                unemployed: g.civic.unemployed.workers, workers: data.workers, assigned: data.assigned, capacitiesInitialized } };
    }
    return { g, act, fast, view, save, assign: (job, delta) => vm.runInContext(`assign('${job}', ${delta})`, context, { timeout: 1000 }) };
}
function prepare(stage, houses = 0, primary = true, multiplier = 1) {
    const env = environment(primary, multiplier);
    function buy(id) {
        for (const resource of Object.values(env.g.resource)) if (resource.max > 0) resource.amount = resource.max;
        env.act(id);
    }
    if (stage >= 1) buy('tech-club');
    if (stage >= 2) buy('tech-bone_tools');
    if (stage >= 3) buy('tech-sundial');
    if (stage >= 4) buy('tech-housing');
    for (let i = 0; i < houses; i++) buy('city-basic_housing');
    for (const resource of Object.values(env.g.resource)) resource.amount = 0;
    return env;
}
const initialization = [true, false].map(primary => ({ primary, expected: environment(primary).view() }));
const cases = [];
for (let stage = 0; stage <= 4; stage++) {
    for (const houses of stage === 4 ? [0, 1, 4, 5] : [0]) {
        const env = prepare(stage, houses);
        for (const id of ['Lumber', 'Stone', 'Food', 'Knowledge']) if (env.g.resource[id].display) env.g.resource[id].amount = 50;
        const input = env.save();
        const available = env.view().available;
        const actions = available.filter(({ costs }) => Object.entries(costs).every(([id, cost]) => env.g.resource[id].amount >= cost)).map(({ id }) => {
            const next = prepare(stage, houses);
            for (const res of ['Lumber', 'Stone', 'Food', 'Knowledge']) if (next.g.resource[res].display) next.g.resource[res].amount = 50;
            // Money remains unavailable in this scope; the sixth cabin needs currency.
            next.act(id);
            return { id, expected: next.view() };
        }).filter(({ id }) => !(id === 'city-basic_housing' && houses === 5));
        cases.push({ name: `stage-${stage}-houses-${houses}`, input, available, actions });
    }
}
const production = [];
for (const [population, food, seed, multiplier] of [[0, 1, 2, 1], [1, 0, 1, 1], [1, .124, 2, 1], [1, .125, 99, 1],
    [1, 250, 2, 1], [4, 250, 42, 1], [5, 250, 23, 1], [0, 0, 2, 1.375]]) {
    const env = prepare(4, 5, true, multiplier);
    env.g.resource.human.amount = population;
    env.g.civic.unemployed.workers = population;
    env.g.resource.Food.amount = food;
    env.g.seed = seed;
    const input = env.save();
    const frames = [];
    for (let i = 1; i <= 240; i++) {
        env.fast(1);
        if ([1, 4, 20, 27, 240].includes(i)) frames.push({ ticks: i * 5, expected: env.view() });
    }
    production.push({ name: `population-${population}-food-${food}-seed-${seed}-multiplier-${multiplier}`, input, frames });
}
function settlement(options = {}) {
    const { houses = 8, farms = 2, yards = 1, quarries = 1, sheds = 0, universities = 0, science = false, banks = 0, banking = false, investing = false,
        metalWorking = false, mines = 0, copperAxes = false, copperPickaxe = false,
        irrigated = false, multiplier = 1, currency = true } = options;
    const env = prepare(4, 0, true, multiplier);
    function buy(id) {
        for (const resource of Object.values(env.g.resource)) if (resource.max > 0) resource.amount = resource.max;
        env.act(id);
    }
    for (const id of ['tech-agriculture', 'tech-stone_axe', 'tech-mining']) buy(id);
    if (currency) buy('tech-currency');
    if (irrigated) buy('tech-irrigation');
    if (sheds > 0) buy('tech-storage');
    if (science || universities > 0) buy('tech-science');
    if (banking || banks > 0) buy('tech-banking');
    for (let i = 0; i < sheds; i++) buy('city-shed');
    for (let i = 0; i < banks; i++) buy('city-bank');
    for (const [id, count] of [['basic_housing', houses], ['farm', farms], ['lumber_yard', yards], ['rock_quarry', quarries]]) {
        for (let i = 0; i < count; i++) buy(`city-${id}`);
    }
    for (let i = 0; i < universities; i++) buy('city-university');
    if (investing) buy('tech-investing');
    if (metalWorking || mines > 0) buy('tech-metal_working');
    for (let i = 0; i < mines; i++) buy('city-mine');
    if (copperAxes) buy('tech-copper_axes');
    if (copperPickaxe) buy('tech-copper_pickaxe');
    for (const resource of Object.values(env.g.resource)) resource.amount = 0;
    return env;
}
for (const options of [{}, { farms: 0, yards: 0, quarries: 0 }, { houses: 10, farms: 4, yards: 5, quarries: 3, irrigated: true },
    { houses: 5, farms: 3, yards: 4, quarries: 2, currency: false },
    { science: true }, { sheds: 1, universities: 1 }, { sheds: 2, universities: 2 },
    { banking: true }, { sheds: 1, banks: 1 }, { sheds: 3, universities: 3, banks: 2, investing: true },
    { sheds: 1, universities: 1, metalWorking: true }, { sheds: 1, universities: 1, mines: 1 },
    { sheds: 2, universities: 2, banks: 1, mines: 3, copperAxes: true, copperPickaxe: true }]) {
    const env = settlement(options);
    function fund(next) {
        for (const id of ['Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper'])
            if (next.g.resource[id].display) next.g.resource[id].amount = Math.max(0, next.g.resource[id].max - 10);
    }
    fund(env);
    const available = env.view().available;
    const actions = available.filter(({ costs }) => Object.entries(costs).every(([id, cost]) => env.g.resource[id].display && cost <= env.g.resource[id].max))
        .map(({ id }) => { const next = settlement(options); fund(next); next.act(id); return { id, expected: next.view() }; });
    cases.push({ name: `settlement-${JSON.stringify(options)}`, input: env.save(), available, actions });
}
for (const options of [
    { population: 8, farmers: 3, lumberjacks: 2, quarriers: 1, farms: 2, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 2, quarriers: 1, farms: 2, food: 0, seed: 1 },
    { population: 5, farmers: 0, lumberjacks: 5, quarriers: 0, farms: 0, food: 0, seed: 1 },
    { population: 5, farmers: 1, lumberjacks: 4, quarriers: 0, farms: 1, food: 0, seed: 2 },
    { population: 8, farmers: 5, lumberjacks: 1, quarriers: 1, farms: 3, food: 50, seed: 42, irrigated: true, multiplier: 1.375 },
    { population: 8, farmers: 2, lumberjacks: 2, quarriers: 2, farms: 1, yards: 3, quarries: 2, food: 250, seed: 79, multiplier: 2.125 },
    { population: 5, farmers: 0, lumberjacks: 1, quarriers: 0, farms: 0, food: 250, seed: 2, multiplier: 1.45 },
    { population: 5, farmers: 0, lumberjacks: 0, quarriers: 0, farms: 0, food: 0, seed: 2, multiplier: 0.1 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, sheds: 1, universities: 1, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 3, quarriers: 3, professors: 2, sheds: 2, universities: 2, food: 0, seed: 1 },
    { population: 8, farmers: 4, lumberjacks: 1, quarriers: 1, professors: 2, sheds: 2, universities: 2, food: 50, seed: 42, irrigated: true, multiplier: 1.375 },
    { population: 5, farmers: 0, lumberjacks: 0, quarriers: 0, professors: 0, professorTarget: 2, sheds: 2, universities: 2, food: 100, seed: 2 },
    { population: 8, farmers: 4, lumberjacks: 1, quarriers: 1, professors: 1, bankers: 1, sheds: 2, universities: 2, banks: 1, investing: true, irrigated: true, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 3, quarriers: 2, professors: 1, bankers: 2, sheds: 2, universities: 2, banks: 2, investing: true, food: 0, seed: 1 },
    { population: 8, farmers: 5, lumberjacks: 0, quarriers: 0, professors: 1, bankers: 2, sheds: 2, universities: 2, banks: 2, investing: true, irrigated: true, multiplier: 1.375, food: 100, seed: 42 },
    { population: 5, farmers: 0, lumberjacks: 0, quarriers: 0, professors: 0, professorTarget: 2, bankerTarget: 2, sheds: 2, universities: 2, banks: 2, investing: true, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 1, sheds: 1, universities: 1, mines: 1, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 2, quarriers: 1, professors: 1, miners: 3, sheds: 2, universities: 2, mines: 3, copperAxes: true, copperPickaxe: true, food: 0, seed: 20 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 2, sheds: 2, universities: 2, mines: 2, copperAxes: true, copperPickaxe: true, irrigated: true, multiplier: 1.375, food: 100, seed: 42 },
    { population: 5, farmers: 0, lumberjacks: 0, quarriers: 0, professors: 0, professorTarget: 2, minerTarget: 2, sheds: 2, universities: 2, mines: 2, food: 100, seed: 2 },
]) {
    const env = settlement(options);
    const { population, farmers, lumberjacks, quarriers, food, seed } = options;
    env.g.resource.human.amount = population;
    env.g.resource.Food.amount = food;
    env.g.seed = seed;
    env.g.civic.unemployed.workers = population;
    env.assign('farmer', farmers);
    env.assign('lumberjack', lumberjacks);
    env.assign('quarry_worker', quarriers);
    env.assign('miner', options.miners || 0);
    if (options.minerTarget) env.g.civic.miner.assigned = options.minerTarget;
    env.assign('professor', options.professors || 0);
    if (options.professorTarget) env.g.civic.professor.assigned = options.professorTarget;
    env.assign('banker', options.bankers || 0);
    if (options.bankerTarget) env.g.civic.banker.assigned = options.bankerTarget;
    const input = env.save();
    const frames = [];
    for (let i = 1; i <= 240; i++) {
        env.fast(1);
        if ([1, 4, 20, 27, 240].includes(i)) frames.push({ ticks: i * 5, expected: env.view() });
    }
    production.push({ name: `working-${JSON.stringify(options)}`, input, frames });
}
const assignments = [];
for (const [job, delta, initial] of [['farmer', 2, 0], ['lumberjack', 100, 0], ['quarry_worker', 1, 0],
    ['farmer', -1, 2], ['lumberjack', -100, 3], ['quarry_worker', -1, 0],
    ['professor', 100, 0], ['professor', 1, 2], ['professor', -1, 2], ['professor', -100, 1],
    ['banker', 100, 0], ['banker', 1, 2], ['banker', -1, 2], ['banker', -100, 1],
    ['miner', 100, 0], ['miner', 1, 2], ['miner', -1, 2], ['miner', -100, 1]]) {
    const env = settlement(job === 'miner' ? { sheds: 2, universities: 1, mines: 2 } : job === 'banker' ? { sheds: 2, universities: 2, banks: 2, investing: true } : job === 'professor' ? { sheds: 2, universities: 2 } : {});
    env.g.resource.human.amount = 5;
    env.g.civic.unemployed.workers = 5;
    env.assign(job, initial);
    const input = env.save();
    const before = env.g.civic[job].workers;
    env.assign(job, delta);
    assignments.push({ name: `${job}-${initial}-${delta}`, input, job, delta,
        applied: Math.abs(env.g.civic[job].workers - before), expected: env.view() });
}
const result = {
    provenance: files.map(file => ({ file, sha256: crypto.createHash('sha256').update(sources[file]).digest('hex') })),
    boundary: 'Human/grassland/standard only. Executes original sentience, resource defaults, selected tech/building actions and costs including sheds/universities/banks/mines, investing and copper axes/pickaxes, job add/sub and finite-job refill, farmerValue, food/growth/knowledge/lumber/stone/miner/income blocks and base/storage/university/bank midLoop caps with original bank_vault. Miner, professor and banker capacity is one per matching building; assigned targets survive deaths. Copper starts at 100 capacity; previously built sheds apply only at the next midLoop after copper unlock, new sheds apply immediately. Investing grants a fed-only 10% tax bonus per banker, with original per-fastLoop rounding. Neutral weather, empty geology, tax rate 20, no power, prestige, events, government, default-job switching or other jobs. Host multiplier is supplied explicitly, Math.random uses persisted seededRandom. Host normalizes workforce at the atomic tick boundary, releasing unemployed then jobs in reverse order after deaths; original delayed labor-anomaly repair is outside this fixture.',
    initialization, cases, production, assignments,
};
const output = JSON.stringify(result, null, 2) + '\n';
const target = path.join(root, 'java/core/src/test/resources/upstream-civilization.json');
if (process.argv.includes('--check')) {
    if (read('java/core/src/test/resources/upstream-civilization.json') !== output) throw new Error('Civilization fixtures changed; regenerate and review.');
} else fs.writeFileSync(target, output);
process.stdout.write(`Verified ${initialization.length} sentience, ${cases.length} settlement, ${production.length} production and ${assignments.length} job scenarios.\n`);
