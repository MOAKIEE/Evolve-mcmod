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
const resourceIds = ['RNA', 'DNA', 'Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'human'];
const traitIds = ['adaptable', 'wasteful', 'creative', 'diverse'];
const cityIds = ['food', 'lumber', 'stone', 'basic_housing'];
const techIds = ['club', 'bone_tools', 'sundial', 'housing'];
const fresh = { biome: 'grassland', universe: 'standard', seeded: false, rapidMutation: false,
    massExtinction: 0, novice: 0, master: 0, grandmaster: 0, unbound: 0, obsolete: 0,
    nightmare: false, extinctSpecies: [], productionMultiplier: 1 };
const adjustmentNames = [...fn('functions', 'adjustCosts').matchAll(/costs = (\w+)\(/g)].map(match => match[1]);
const adjustments = ['adjustCosts', ...adjustmentNames, 'craftAdjust'].map(name => fn('functions', name)).join('\n');
function environment(primary = true, multiplier = 1) {
    const g = { race: { species: 'human', universe: 'standard' },
        resource: { RNA: { amount: 300, max: 500, display: true, delta: 0 }, DNA: { amount: 300, max: 500, display: true, delta: 0 } },
        tech: { evo: 7, evo_humanoid: primary ? 2 : 1 }, evolution: {}, city: { biome: 'grassland', ptrait: [], calendar: { day: 0 } },
        civic: { govern: {}, foreign: { gov0: {}, gov1: {}, gov2: {} }, garrison: { workers: 0 }, homeless: 0 },
        stats: { achieve: {}, feat: {}, starved: 0, cfood: 0, tfood: 0, clumber: 0, tlumber: 0, cstone: 0, tstone: 0, know: 0 },
        genes: { minor: {} }, blood: {}, prestige: {}, portal: {}, space: {}, interstellar: {}, galaxy: {}, tauceti: {}, arpa: {}, eden: {},
        settings: { boring: true, mtorder: [], tabLoad: false, pause: false }, queue: {}, seed: 2 };
    const temporary = { resource: {} };
    let names = 0;
    const sandbox = { global: g, tmp_vars: temporary, p_on: {}, support_on: {}, breakdown: { p: { consume: {} } },
        loc: key => key, $: value => [value], deepClone: structuredClone,
        genus_def: { humanoid: { traits: { adaptable: 1, wasteful: 1 } } },
        races: { human: { type: 'humanoid', traits: { creative: 1, diverse: 1 } } },
        traits: {}, planetTraits: {}, govActive: () => 0, fathomCheck: () => 0,
        eventActive: () => ({ active: false }), getHalloween: () => ({ active: false }),
        clearElement: () => {}, registerTech: () => {}, addAction: () => {},
        genCivName: () => ({ s0: names++, s1: 'fixture' }),
        messageQueue: () => {}, flib: () => 'human', calcPillar: () => {}, commisionGarrison: () => {},
        defineGovernment: () => {}, calc_mastery: () => {}, altRace: () => {}, tagEvent: () => {}, alevel: () => 1,
        loadTab: () => {}, loadSpecialResource: () => {}, blubberFill: () => {},
        loadResource: (id, wiki, max) => { g.resource[id] ??= { amount: 0, max, display: false, delta: 0 }; },
        loadJob: (id, define, impact) => { g.civic[id] = { workers: 0, display: false, impact }; },
        workerScale: n => n, highPopAdjust: n => n, jobName: id => id, racialTrait: () => 1,
        syndicate: () => 1, production: () => 1, astroSign: '', gene_sequence: false,
    };
    const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    const city = cityIds.map(id => block('actions', id, 8)).join('\n');
    const tech = techIds.map(id => block('tech', id, 4)).join('\n');
    const functions = [fn('vars', 'seededRandom'), fn('functions', 'costMultiplier'), fn('functions', 'modRes'),
        fn('actions', 'payCosts'), fn('actions', 'checkCosts'), fn('actions', 'initStruct'), fn('space', 'incrementStruct'),
        fn('races', 'setTraitRank'), fn('races', 'combineTraits'), fn('resources', 'defineResources'),
        fn('jobs', 'defineJobs'), fn('actions', 'sentience'), adjustments].join('\n');
    vm.runInContext(`${functions}\nvar actions = { city: {${city}}, tech: {${tech}},
        space: { spc_red: { biodome: { title: () => 'biodome' } } } };
        Math.random = () => seededRandom(0, 1);
        ${sources['src/vars.js'].slice(sources['src/vars.js'].indexOf('Math.rand ='), sources['src/vars.js'].indexOf("global['seed']"))}
        function fast() {
            const global_multiplier = ${multiplier}; const time_multiplier = .25;
            const date = new Date('2026-01-01T00:00:00Z'); const hunger = 1; const q_multiplier = 1; const zigVal = 1;
            global.civic.unemployed.workers = global.resource.human.amount;
            ${section('        // Consumption\n', '        // Fortress Repair\n')}
            ${section('        // Citizen Growth\n', "        if (global.space['shipyard']")}
            ${section('        // Knowledge\n', '        // Omniscience\n')}
        }
        sentience();`, context, { timeout: 1000 });
    for (const id of resourceIds) {
        temporary.resource[id] = { temp_max: g.resource[id].max };
        sandbox.breakdown.p[id] = {};
        sandbox.breakdown.p.consume[id] = {};
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
            for (const id of resourceIds) g.resource[id].amount = Math.min(g.resource[id].max, g.resource[id].amount);
        }
    }
    function view() {
        return {
            resources: Object.fromEntries(resourceIds.map(id => [id, { amount: g.resource[id].amount, capacity: g.resource[id].max,
                visible: g.resource[id].display, changePerSecond: 0 }])),
            structures: Object.fromEntries(Object.entries(g.city).filter(([id]) => id === 'basic_housing').map(([id, v]) => [id, v.count])),
            technologies: { ...g.tech }, traits: Object.fromEntries(traitIds.map(id => [id, g.race[id]])), seed: g.seed,
            available: Object.entries({ ...Object.fromEntries(cityIds.map(id => [`city-${id}`, sandbox.actions.city[id]])),
                ...Object.fromEntries(techIds.map(id => [`tech-${id}`, sandbox.actions.tech[id]])) }).filter(([, rule]) => visible(rule))
                .map(([id, rule]) => ({ id, costs: Object.fromEntries(Object.entries(sandbox.adjustCosts(rule)).map(([res, cost]) => [res, cost()]).filter(([, cost]) => cost > 0)) })),
        };
    }
    function save() {
        const data = view();
        return { schemaVersion: 2, ruleset: 'evolve-1.4.10/primitive-1', elapsedTicks: 0, revision: 0, tickRemainder: 0, loopCycle: 0,
            paused: false, phase: 'PRIMITIVE', randomSeed: g.seed, species: 'human', genus: 'humanoid', progress: 100,
            context: { ...fresh, productionMultiplier: multiplier }, resources: data.resources, structures: data.structures,
            technologies: data.technologies, traits: data.traits, civilization: { primaryGenus: primary ? 'humanoid' : '', unemployed: g.resource.human.amount } };
    }
    return { g, act, fast, view, save };
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
        const actions = available.map(({ id }) => {
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
const result = {
    provenance: files.map(file => ({ file, sha256: crypto.createHash('sha256').update(sources[file]).digest('hex') })),
    boundary: 'Human/grassland/standard only. Executes original sentience, resource defaults, trait ranks, selected actions, cost functions, food/growth/knowledge blocks. UI and unported systems stubbed; no prestige, events, calendar/weather or other jobs. Math.random uses persisted seededRandom to test the same original probability expressions deterministically. Host global multiplier is supplied explicitly. Population/job synchronization is tested separately at the Java atomic tick boundary.',
    initialization, cases, production,
};
const output = JSON.stringify(result, null, 2) + '\n';
const target = path.join(root, 'java/core/src/test/resources/upstream-civilization.json');
if (process.argv.includes('--check')) {
    if (read('java/core/src/test/resources/upstream-civilization.json') !== output) throw new Error('Civilization fixtures changed; regenerate and review.');
} else fs.writeFileSync(target, output);
process.stdout.write(`Verified ${initialization.length} sentience, ${cases.length} primitive and ${production.length} production scenarios.\n`);
