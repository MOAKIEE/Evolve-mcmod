// Development-only execution of ORIGINAL primitive rules. No JS is shipped with the Java core.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8').replace(/\r\n/g, '\n');
const files = ['src/actions.js', 'src/tech.js', 'src/main.js', 'src/functions.js', 'src/resources.js', 'src/races.js', 'src/vars.js', 'src/space.js', 'src/jobs.js', 'src/industry.js', 'src/civics.js'];
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
const craftResources = ['Plywood', 'Brick', 'Wrought_Iron'];
const resourceIds = ['RNA', 'DNA', 'Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper', 'Iron', 'Cement', 'Furs', ...craftResources, 'human'];
const traitIds = ['adaptable', 'wasteful', 'creative', 'diverse'];
const structureIds = ['basic_housing', 'farm', 'lumber_yard', 'rock_quarry', 'shed', 'university', 'bank', 'mine', 'smelter', 'cement_plant', 'sawmill', 'foundry', 'garrison', 'library'];
const cityIds = ['food', 'lumber', 'stone', ...structureIds];
const techIds = ['club', 'bone_tools', 'sundial', 'housing', 'agriculture', 'irrigation', 'currency', 'stone_axe', 'mining', 'storage', 'science', 'banking', 'investing', 'metal_working', 'copper_axes', 'copper_pickaxe', 'iron_mining', 'iron_axes', 'iron_pickaxe', 'smelting', 'cement', 'iron_saw', 'farm_house', 'reinforced_shed', 'foundry', 'artisans', 'garrison', 'library', 'thesis', 'research_grant', 'apprentices', 'carpentry'];
const jobIds = ['farmer', 'lumberjack', 'quarry_worker', 'miner', ...craftResources.map(r => `craft_${r}`), 'cement_worker', 'professor', 'banker'];
const fresh = { biome: 'grassland', universe: 'standard', seeded: false, rapidMutation: false,
    massExtinction: 0, novice: 0, master: 0, grandmaster: 0, unbound: 0, obsolete: 0,
    nightmare: false, extinctSpecies: [], productionMultiplier: 1 };
const adjustmentNames = [...fn('functions', 'adjustCosts').matchAll(/costs = (\w+)\(/g)].map(match => match[1]);
const adjustments = ['adjustCosts', ...adjustmentNames, 'craftAdjust'].map(name => fn('functions', name)).join('\n');
function environment(primary = true, multiplier = 1) {
    const g = { race: { species: 'human', universe: 'standard' },
        resource: { RNA: { amount: 300, max: 500, display: true, delta: 0 }, DNA: { amount: 300, max: 500, display: true, delta: 0 } },
        tech: { evo: 7, evo_humanoid: primary ? 2 : 1 }, evolution: {}, city: { biome: 'grassland', ptrait: [], geology: {}, calendar: { day: 0 } },
        civic: { govern: {}, foreign: { gov0: {}, gov1: {}, gov2: {} }, garrison: { workers: 0, progress: 0, rate: 0, max: 0, wounded: 0, crew: 0, display: false }, homeless: 0, taxes: { tax_rate: 20 } },
        stats: { achieve: {}, feat: {}, starved: 0, cfood: 0, tfood: 0, clumber: 0, tlumber: 0, cstone: 0, tstone: 0, know: 0 },
        genes: { minor: {} }, blood: {}, prestige: { Plasmid: { count: 0 } }, portal: {}, space: {}, interstellar: {}, galaxy: {}, tauceti: {}, arpa: {}, eden: {},
        settings: { boring: true, mtorder: [], tabLoad: false, pause: false, msgFilters: { combat: {} } }, queue: {}, seed: 2 };
    const temporary = { resource: {} };
    let names = 0;
    let loops = 0;
    let capacitiesInitialized = false;
    const sandbox = { global: g, tmp_vars: temporary, p_on: {}, support_on: {}, breakdown: { p: { consume: {} }, c: {} },
        loc: key => key, $: value => [value], deepClone: structuredClone,
        genus_def: { humanoid: { traits: { adaptable: 1, wasteful: 1 } } },
        races: { human: { type: 'humanoid', traits: { creative: 1, diverse: 1 } } },
        traits: {}, planetTraits: {}, biomes: { grassland: { vars: () => [1.2] } }, govActive: () => 0, fathomCheck: () => 0,
        BHStorageMulti: n => n, spatialReasoning: n => Math.round(n), getShrineBonus: () => ({ mult: 1 }), structName: id => id,
        housingLabel: () => 'housing', traitCostMod: (trait, cost) => cost, isStargateOn: () => false, shrineBonusActive: () => false,
        powerOnNewStruct: () => {}, loadFoundry: () => {}, traitRank: id => g.race[id], templeCount: () => 0, faithBonus: () => 0,
        defineIndustry: () => {},
        checkAltPurgatory: (region, id, alternate, value) => { g[region][id] = structuredClone(value); },
        eventActive: type => type === 'summer' ? false : ({ active: false }), getHalloween: () => ({ active: false }),
        clearElement: () => {}, registerTech: () => {}, addAction: () => {}, vBind: () => {},
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
        fn('actions', 'payCosts'), fn('actions', 'checkCosts'), fn('actions', 'initStruct'), fn('actions', 'bank_vault'), fn('actions', 'dirt_adjust'), fn('actions', 'storageMultipler'), fn('space', 'incrementStruct'),
        fn('races', 'setTraitRank'), fn('races', 'combineTraits'), fn('resources', 'defineResources'),
        fn('jobs', 'defineJobs'), fn('jobs', 'farmerValue'), fn('jobs', 'jobScale'), fn('actions', 'sentience'),
        fn('civics', 'weaponTechModifer'), fn('civics', 'armyRating'), fn('civics', 'garrisonSize'), adjustments].join('\n');
    const industryFunctions = ['smelterFuelConfig', 'smelterUnlocked', 'addSmelter'].map(name => fn('industry', name)).join('\n');
    const smelterSource = fn('industry', 'loadSmelter');
    const smelterMethods = smelterSource.slice(smelterSource.indexOf('            addFuel(type){'), smelterSource.indexOf('            buildLabel(type){'));
    const jobSource = fn('jobs', 'loadJob');
    const jobMethods = jobSource.slice(jobSource.lastIndexOf('                add(){'), jobSource.indexOf('                level(job){'));
    const foundrySource = fn('jobs', 'loadFoundry');
    const foundryMethods = foundrySource.slice(foundrySource.indexOf('                add(res){'), foundrySource.indexOf('                level(){'));
    const resourceSource = fn('resources', 'loadResource');
    const manualMethod = resourceSource.slice(resourceSource.indexOf('            craft(res,vol){'), resourceSource.indexOf('            craftCost(res,vol){'));
    const ratioSource = sources['src/resources.js'].slice(sources['src/resources.js'].indexOf('export const craftingRatio ='), sources['src/resources.js'].indexOf('export function initResourceTabs')).replace('export const', 'const');
    const craftingFunctions = `${fn('resources', 'craftCost')}\n${ratioSource}\nvar traits = {${block('races', 'wasteful', 4)}${block('races', 'diverse', 4)}};`;
    const midSource = fn('main', 'midLoop');
    const midBase = midSource.slice(midSource.indexOf('        var caps = {', midSource.indexOf('        // Resource caps')), midSource.indexOf('        var lCaps ='));
    const midStorage = midSource.slice(midSource.indexOf("        if (global.city['rock_quarry']){"), midSource.indexOf("        else if (global.city['graveyard']){"));
    const sawmillCaps = midSource.slice(midSource.indexOf("        if (global.city['sawmill']){"), midSource.indexOf("        if (global.city['mine']){"));
    const farmCaps = midSource.slice(midSource.indexOf("        if (global.city['farm']){"), midSource.indexOf("        if (global.city['wharf']){"));
    const housingCaps = midSource.slice(midSource.indexOf("        if (global.city['basic_housing']){"), midSource.indexOf("        if (global.tauceti['tau_housing']"));
    const shedStorage = section("        if (global.city['shed']){", "        if (global.race['lone_survivor']){");
    const universityCaps = section("        if (global.city['university']){", "        if (global.race['lone_survivor'] && global.tauceti['alien_outpost']){");
    const libraryCaps = midSource.slice(midSource.indexOf("        if (global.city['library']){"), midSource.indexOf("        if (global.city['wardenclyffe']){"));
    const bankCaps = section("        if (global.city['bank'] || (global.race['cataclysm'] && p_on['spaceport'])){", "        if (global.eden['eternal_bank']){\n            let vault");
    const refillSource = section("        Object.keys(job_desc).forEach(function (job){", '        let entertainment = 0;');
    const program = `${functions}\n${industryFunctions}\n${craftingFunctions}\nvar actions = { city: {${city}}, tech: {${tech}},
        space: { spc_red: { biodome: { title: () => 'biodome' } } } };
        Math.random = () => seededRandom(0, 1);
        ${sources['src/vars.js'].slice(sources['src/vars.js'].indexOf('Math.rand ='), sources['src/vars.js'].indexOf("global['seed']"))}
        function fast() {
            const global_multiplier = ${multiplier}; const time_multiplier = .25;
            const date = new Date('2026-01-01T00:00:00Z'); const q_multiplier = 1; const zigVal = 1;
            const job_desc = ${JSON.stringify(Object.fromEntries([...jobIds.filter(id => !id.startsWith('craft_')), 'craftsman'].map(id => [id, 'fixture'])))};
            ${refillSource}
            ${section('        // Consumption\n', '        // Fortress Repair\n')}
            ${section('        // Citizen Growth\n', "        if (global.space['shipyard']")}
            let hunger = fed ? 1 : .5;
            ${section('        // Furs\n', "        if (global.resource.Furs.display && global.tech['isolation']")}
            ${section('        // Knowledge\n', '        // Omniscience\n')}
            ${section('        // Cement\n', '        let shrineMetal =')}
            const shrineMetal = { mult: 1 };
            ${section('        // Smelters\n', '        // Graphene\n')}
            ${section('        // Lumber\n', "        let refinery = global.city['metal_refinery']")}
            ${section('        // Stone / Amber\n', "        // Crystal\n")}
            const refinery = 0; const e_ship = {}; const qs_multiplier = 1;
            ${section('        // Miners\n', '        // Mars Mining\n')}
            const FactoryMoney = 0;
            ${section('        // Income\n', "        if (global.tech['gambling']")}
            ${section('        // Crafting\n', '        // Detect new unlocks\n')}
            ${section("    if (global.civic['garrison'] && global.civic.garrison.workers < global.civic.garrison.max){", '    // carport repair\n')}
        }
        function mid() {
            ${midBase}
            ${midStorage}
            ${sawmillCaps}
            ${shedStorage}
            const lCaps = { professor: 0 };
            ${universityCaps}
            ${libraryCaps}
            ${bankCaps}
            caps[global.race.species] = 0;
            ${farmCaps}
            ${housingCaps}
            for (const id of ['Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper', 'Iron', 'Cement', 'Furs', 'human']) global.resource[id].max = caps[id];
            global.resource.human.display = caps.human > 0;
            global.civic.professor.max = lCaps.professor;
            global.civic.banker.max = jobScale(global.city.bank?.count || 0);
            global.civic.miner.max = jobScale(global.city.mine?.count || 0);
            global.civic.cement_worker.max = jobScale((global.city.cement_plant?.count || 0) * 2);
        }
        function assign(job, delta) {
            const keyMultiplier = () => Math.abs(delta);
            if (job.startsWith('craft_')) {
                const servants = false;
                const controls = { ${foundryMethods} };
                controls[delta > 0 ? 'add' : 'sub'](job.substring(6));
                return;
            }
            const controls = { ${jobMethods} };
            controls[delta > 0 ? 'add' : 'sub']();
        }
        function adjustSmelter(id, delta) {
            const keyMultiplier = () => Math.abs(delta);
            const controls = { ${smelterMethods} };
            const method = id === 'Wood' ? (delta > 0 ? 'addFuel' : 'subFuel') : (delta > 0 ? 'addMetal' : 'subMetal');
            controls[method](id);
        }
        function manual(res, volume) {
            const keyMultiplier = () => 1;
            craftingRatio('','',true);
            const controls = { ${manualMethod} };
            controls.craft(res, volume);
        }
        sentience();`;
    vm.runInContext(program, context, { timeout: 1000 });
    for (const id of ['unemployed', ...jobIds.filter(id => !id.startsWith('craft_'))]) g.civic[id].max = ['miner', 'cement_worker', 'professor', 'banker'].includes(id) ? 0 : -1;
    g.civic.craftsman.max = 0;
    const jobWorkers = id => id.startsWith('craft_') ? (g.city.foundry?.[id.substring(6)] || 0) : g.civic[id].workers;
    for (const id of Object.keys(g.resource)) {
        temporary.resource[id] = { temp_max: g.resource[id].max };
        sandbox.breakdown.p[id] = {};
        sandbox.breakdown.p.consume[id] = {};
        sandbox.breakdown.c[id] = {};
    }
    // Atomic host invariant: release unemployed first, then jobs in reverse order after deaths.
    function normalizeWorkers() {
        let excess = g.civic.unemployed.workers + jobIds.reduce((sum, id) => sum + jobWorkers(id), 0) - g.resource.human.amount;
        for (const id of ['unemployed', ...jobIds.toReversed()]) {
            const loss = Math.min(jobWorkers(id), Math.max(0, excess));
            if (id.startsWith('craft_') && g.city.foundry) {
                g.city.foundry[id.substring(6)] -= loss;
                g.city.foundry.crafting -= loss;
                g.civic.craftsman.workers -= loss;
            } else if (!id.startsWith('craft_')) g.civic[id].workers -= loss;
            excess -= loss;
        }
    }
    const visible = rule => Object.entries(rule.reqs || {}).every(([id, rank]) => (g.tech[id] || 0) >= rank) &&
        (!rule.grant || (g.tech[rule.grant[0]] || 0) < rule.grant[1]) && (!rule.condition || rule.condition());
    function act(id, repetitions = 1) {
        const [category, name] = id.split('-');
        if (category === 'craft') {
            const before = g.resource[name].amount;
            const bonus = vm.runInContext(`craftingRatio('','',true); craftingRatio('${name}','manual').multiplier`, context);
            vm.runInContext(`manual('${name}', ${repetitions})`, context);
            return Math.round((g.resource[name].amount - before) / bonus);
        }
        const rule = sandbox.actions[category][name];
        let applied = 0;
        for (let i = 0; i < repetitions; i++) {
            if (!visible(rule)) break;
            const succeeded = rule.action.call(rule, {});
            if (succeeded) applied++;
            if (succeeded && rule.grant) g.tech[rule.grant[0]] = rule.grant[1];
        }
        return applied;
    }
    function fast(count) {
        for (let i = 0; i < count; i++) {
            for (const id of resourceIds) temporary.resource[id].temp_max = g.resource[id].max + g.resource[id].amount;
            vm.runInContext('fast()', context, { timeout: 1000 });
            normalizeWorkers();
            for (const id of resourceIds) if (g.resource[id].max >= 0) g.resource[id].amount = Math.min(g.resource[id].max, g.resource[id].amount);
            if (++loops % 4 === 0) { vm.runInContext('mid()', context, { timeout: 1000 }); capacitiesInitialized = true; }
        }
    }
    function view() {
        return {
            resources: Object.fromEntries(resourceIds.map(id => [id, { amount: g.resource[id].amount, capacity: g.resource[id].max,
                visible: g.resource[id].display, changePerSecond: 0 }])),
            structures: Object.fromEntries(Object.entries(g.city).filter(([id]) => structureIds.includes(id)).map(([id, v]) => [id, v.count])),
            technologies: { ...g.tech }, traits: Object.fromEntries(traitIds.map(id => [id, g.race[id]])), seed: g.seed,
            unemployed: g.civic.unemployed.workers, workers: Object.fromEntries(jobIds.map(id => [id, jobWorkers(id)])),
            assigned: Object.fromEntries(jobIds.map(id => [id, id.startsWith('craft_') ? jobWorkers(id) : g.civic[id].assigned])),
            smelter: { wood: g.city.smelter?.Wood || 0, iron: g.city.smelter?.Iron || 0 },
            garrison: { soldiers: g.civic.garrison.workers, trainingProgress: g.civic.garrison.progress, trainingRate: g.civic.garrison.rate },
            professorImpact: g.civic.professor.impact,
            capacitiesInitialized,
            available: Object.entries({ ...Object.fromEntries(cityIds.map(id => [`city-${id}`, sandbox.actions.city[id]])),
                ...Object.fromEntries(techIds.map(id => [`tech-${id}`, sandbox.actions.tech[id]])) }).filter(([, rule]) => visible(rule))
                .map(([id, rule]) => ({ id, costs: Object.fromEntries(Object.entries(sandbox.adjustCosts(rule)).map(([res, cost]) => [res, cost()]).filter(([, cost]) => cost > 0)) }))
                .concat(g.city.foundry?.count > 0 ? craftResources.filter(id => g.resource[id].display).map(id => ({ id: `craft-${id}`,
                    costs: Object.fromEntries(sandbox.craftCost(true)[id].map(cost => [cost.r, cost.a])) })) : []),
        };
    }
    function save() {
        const data = view();
        return { schemaVersion: 14, ruleset: 'evolve-1.4.10/carpentry-1', elapsedTicks: 0, revision: 0, tickRemainder: 0, loopCycle: 0,
            paused: false, phase: 'PRIMITIVE', randomSeed: g.seed, species: 'human', genus: 'humanoid', progress: 100,
            context: { ...fresh, productionMultiplier: multiplier }, resources: data.resources, structures: data.structures,
            technologies: data.technologies, traits: data.traits, civilization: { primaryGenus: primary ? 'humanoid' : '',
                unemployed: g.civic.unemployed.workers, workers: data.workers, assigned: data.assigned, capacitiesInitialized, smelter: data.smelter, garrison: data.garrison, professorImpact: data.professorImpact } };
    }
    return { g, act, fast, view, save,
        mid: () => { vm.runInContext('mid()', context, { timeout: 1000 }); capacitiesInitialized = true; },
        adjustSmelter: (id, delta) => vm.runInContext(`adjustSmelter('${id}', ${delta})`, context, { timeout: 1000 }),
        assign: (job, delta) => vm.runInContext(`assign('${job}', ${delta})`, context, { timeout: 1000 }) };
}
function prepare(stage, houses = 0, primary = true, multiplier = 1) {
    const env = environment(primary, multiplier);
    function buy(id) {
        for (const resource of Object.values(env.g.resource)) if (resource.max > 0) resource.amount = resource.max;
        if (env.act(id) !== 1) throw new Error(`Cannot fund fixture setup action: ${id}; options=${JSON.stringify(options)}; costs=${JSON.stringify(action.costs)}`);
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
        ironMining = false, ironAxes = false, ironPickaxe = false,
        smelting = false, smelters = 0,
        cement = false, cementPlants = 0, ironSaw = false, sawmills = 0, farmHouse = false, reinforcedShed = false, upgradedSheds = 0, foundries = 0, foundry = false, artisans = false, apprentices = false, carpentry = false, military = false, garrisons = 0,
        irrigated = false, multiplier = 1, currency = true, primary = true, library = false, libraries = 0, thesis = false, researchGrant = false } = options;
    const env = prepare(4, 0, primary, multiplier);
    function buy(id) {
        for (const resource of Object.values(env.g.resource)) if (resource.max > 0) resource.amount = resource.max;
        const action = env.view().available.find(action => action.id === id);
        if (!action) throw new Error(`Fixture setup action is locked: ${id}`);
        for (const [resource, cost] of Object.entries(action.costs)) {
            if (craftResources.includes(resource)) {
                for (let attempts = 0; env.g.resource[resource].amount < cost; attempts++) {
                    if (attempts >= 1000) throw new Error(`Fixture crafting stalled: ${resource}`);
                    buy(`craft-${resource}`);
                }
            }
        }
        if (env.act(id) !== 1) throw new Error(`Cannot fund fixture setup action: ${id}; options=${JSON.stringify(options)}; costs=${JSON.stringify(action.costs)}`);
    }
    for (const id of ['tech-agriculture', 'tech-stone_axe', 'tech-mining']) buy(id);
    if (currency) buy('tech-currency');
    if (irrigated) buy('tech-irrigation');
    if (sheds > 0) buy('tech-storage');
    if (science || universities > 0) buy('tech-science');
    if (banking || banks > 0) buy('tech-banking');
    const initialSheds = Math.min(sheds, 3);
    for (let i = 0; i < initialSheds; i++) buy('city-shed');
    for (let i = 0; i < banks; i++) buy('city-bank');
    for (let i = initialSheds; i < sheds; i++) buy('city-shed');
    for (const [id, count] of [['basic_housing', houses], ['farm', farms], ['lumber_yard', yards], ['rock_quarry', quarries]]) {
        for (let i = 0; i < count; i++) buy(`city-${id}`);
    }
    for (let i = 0; i < universities; i++) buy('city-university');
    if (investing) buy('tech-investing');
    if (metalWorking || mines > 0) buy('tech-metal_working');
    for (let i = 0; i < mines; i++) buy('city-mine');
    if (copperAxes) buy('tech-copper_axes');
    if (copperPickaxe) buy('tech-copper_pickaxe');
    if (ironMining) buy('tech-iron_mining');
    if (ironAxes || ironPickaxe) env.mid();
    if (ironAxes) buy('tech-iron_axes');
    if (ironPickaxe) buy('tech-iron_pickaxe');
    if (smelting || smelters > 0) buy('tech-smelting');
    if (smelters > 0) env.mid();
    for (let i = 0; i < smelters; i++) buy('city-smelter');
    if (cement || cementPlants > 0) buy('tech-cement');
    for (let i = 0; i < cementPlants; i++) buy('city-cement_plant');
    if (ironSaw || sawmills > 0) { env.mid(); buy('tech-iron_saw'); }
    for (let i = 0; i < sawmills; i++) buy('city-sawmill');
    if (farmHouse) buy('tech-farm_house');
    if (reinforcedShed) { env.mid(); buy('tech-reinforced_shed'); }
    for (let i = 0; i < upgradedSheds; i++) buy('city-shed');
    if (foundry || foundries > 0 || artisans || apprentices || carpentry) buy('tech-foundry');
    if (foundries > 0) env.mid();
    for (let i = 0; i < foundries; i++) buy('city-foundry');
    if (artisans || apprentices || carpentry) buy('tech-artisans');
    if (apprentices || carpentry) buy('tech-apprentices');
    if (military || garrisons > 0) buy('tech-garrison');
    for (let i = 0; i < garrisons; i++) buy('city-garrison');
    if (library || libraries > 0 || thesis || researchGrant) buy('tech-library');
    for (let i = 0; i < libraries; i++) buy('city-library');
    if (thesis || researchGrant) buy('tech-thesis');
    if (researchGrant) buy('tech-research_grant');
    if (carpentry) { env.mid(); buy('tech-carpentry'); }
    for (const resource of Object.values(env.g.resource)) resource.amount = 0;
    return env;
}
for (const options of [{}, { farms: 0, yards: 0, quarries: 0 }, { houses: 10, farms: 4, yards: 5, quarries: 3, irrigated: true },
    { houses: 5, farms: 3, yards: 4, quarries: 2, currency: false },
    { science: true }, { sheds: 1, universities: 1 }, { sheds: 2, universities: 2 },
    { banking: true }, { sheds: 1, banks: 1 }, { sheds: 3, universities: 3, banks: 2, investing: true },
    { sheds: 1, universities: 1, metalWorking: true }, { sheds: 1, universities: 1, mines: 1 },
    { sheds: 2, universities: 2, banks: 1, mines: 3, copperAxes: true, copperPickaxe: true },
    { sheds: 8, universities: 5, banks: 2, mines: 1, copperAxes: true, copperPickaxe: true },
    { sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, copperAxes: true, copperPickaxe: true },
    { sheds: 15, universities: 7, banks: 5, mines: 3, copperAxes: true, copperPickaxe: true, ironMining: true, ironAxes: true, ironPickaxe: true },
    { sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, smelting: true },
    { sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, smelters: 2 },
    { sheds: 6, universities: 1, banks: 2, cement: true },
    { sheds: 7, universities: 1, banks: 2, cementPlants: 1 },
    { sheds: 15, universities: 7, banks: 5, mines: 1, ironMining: true, ironSaw: true },
    { sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, cementPlants: 2, sawmills: 2 },
    { sheds: 1, universities: 1, farmHouse: true },
    { houses: 0, sheds: 1, universities: 1, farmHouse: true },
    { sheds: 10, universities: 5, banks: 3, mines: 1, ironMining: true, cementPlants: 1, reinforcedShed: true },
    { sheds: 10, universities: 5, banks: 3, mines: 1, ironMining: true, cementPlants: 1, reinforcedShed: true, upgradedSheds: 1 },
    { sheds: 5, universities: 3, banks: 2, mines: 1, foundry: true },
    { sheds: 5, universities: 3, banks: 2, mines: 1, foundries: 1 },
    { sheds: 5, universities: 3, banks: 2, mines: 1, foundries: 3, artisans: true },
    { sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, cementPlants: 1, foundries: 3, artisans: true },
    { sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, cementPlants: 1, foundries: 3, artisans: true, primary: false },
    { sheds: 15, universities: 7, banks: 5, mines: 1, foundries: 3, apprentices: true },
    { sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 1, ironMining: true, cementPlants: 1, sawmills: 2, foundries: 4, garrisons: 1, libraries: 6, researchGrant: true, apprentices: true },
    { sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 1, ironMining: true, cementPlants: 1, sawmills: 2, foundries: 4, garrisons: 1, libraries: 6, researchGrant: true, carpentry: true },
    { sheds: 1, universities: 1, military: true },
    { sheds: 1, universities: 1, garrisons: 1 },
    { sheds: 2, universities: 1, garrisons: 2 },
    { sheds: 10, universities: 5, banks: 3, mines: 1, ironMining: true, cementPlants: 1, reinforcedShed: true, garrisons: 1 },
    { sheds: 6, universities: 2, banks: 2, cement: true, library: true },
    { sheds: 8, universities: 5, banks: 2, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 1 },
    { sheds: 8, universities: 5, banks: 2, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 2, thesis: true },
    { sheds: 15, universities: 7, banks: 5, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 3, researchGrant: true }]) {
    const env = settlement(options);
    function fund(next) {
        for (const id of ['Food', 'Lumber', 'Stone', 'Knowledge', 'Money', 'Copper', 'Iron', 'Cement', 'Furs'])
            if (next.g.resource[id].display) next.g.resource[id].amount = Math.max(0, next.g.resource[id].max - 10);
        for (const id of craftResources) if (next.g.resource[id].display) next.g.resource[id].amount = 1000;
    }
    fund(env);
    const available = env.view().available;
    const actions = available.filter(({ costs }) => Object.entries(costs).every(([id, cost]) => env.g.resource[id].display && (env.g.resource[id].max < 0 || cost <= env.g.resource[id].max)))
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
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 1, sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 2, quarriers: 1, professors: 1, miners: 3, sheds: 15, universities: 7, banks: 5, mines: 3, copperAxes: true, copperPickaxe: true, ironMining: true, ironAxes: true, ironPickaxe: true, food: 0, seed: 20 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 2, sheds: 15, universities: 7, banks: 5, mines: 2, copperAxes: true, copperPickaxe: true, ironMining: true, ironAxes: true, ironPickaxe: true, irrigated: true, multiplier: 1.375, food: 100, seed: 42 },
    ...[0, 0.749, 0.75, 1.499, 1.5, 100].map(lumber => ({ population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 2, sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, smelters: 2, lumber, food: 100, seed: 2 })),
    { population: 8, farmers: 0, lumberjacks: 2, quarriers: 1, professors: 1, miners: 3, sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 3, copperAxes: true, copperPickaxe: true, ironMining: true, ironAxes: true, ironPickaxe: true, smelters: 3, lumber: 10, food: 0, seed: 20, multiplier: 1.375 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 1, miners: 2, sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, smelters: 3, lumber: 100, ironSlots: 0, food: 100, seed: 2 },
    ...[0, 0.749, 0.75, 1.499, 1.5, 100].map(stone => ({ population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, cementWorkers: 2, sheds: 7, universities: 1, banks: 2, cementPlants: 1, stone, food: 100, seed: 2 })),
    { population: 8, farmers: 0, lumberjacks: 1, quarriers: 1, miners: 1, cementWorkers: 3, professors: 1, sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, cementPlants: 2, sawmills: 2, stone: 10, food: 0, seed: 20, multiplier: 1.375 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, cementWorkers: 1, professors: 1, sheds: 7, universities: 1, banks: 2, cementPlants: 1, stone: 100, food: 100, seed: 42, multiplier: 1.375 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, farms: 3, sheds: 1, universities: 1, farmHouse: true, food: 100, seed: 2 },
    { population: 0, farmers: 0, lumberjacks: 0, quarriers: 0, houses: 0, farms: 2, sheds: 1, universities: 1, farmHouse: true, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, cementWorkers: 1, professors: 1, sheds: 10, universities: 5, banks: 3, mines: 1, ironMining: true, cementPlants: 1, reinforcedShed: true, stone: 100, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, cementWorkers: 1, professors: 1, sheds: 10, universities: 5, banks: 3, mines: 1, ironMining: true, cementPlants: 1, reinforcedShed: true, upgradedSheds: 1, stone: 100, food: 100, seed: 2 },
    ...[0, 110 / 140 - 1e-9, 110 / 140, 100].map(lumber => ({ population: 8, farmers: 3, lumberjacks: 0, quarriers: 1, craftPlywood: 2, sheds: 5, universities: 3, banks: 2, mines: 1, foundries: 3, artisans: true, lumber, food: 100, seed: 2 })),
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, craftBrick: 1, craftIron: 1, sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, cementPlants: 1, foundries: 3, artisans: true, cementInput: 10, ironInput: 10, food: 100, seed: 2, multiplier: 1.375 },
    { population: 8, farmers: 0, lumberjacks: 0, quarriers: 0, craftPlywood: 2, craftBrick: 1, craftIron: 1, sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, cementPlants: 1, foundries: 4, artisans: true, lumber: 100, cementInput: 10, ironInput: 10, food: 0, seed: 20, multiplier: 0.1, primary: false },
    ...[0, 1, 2, 3].map(craftPlywood => ({ population: 8, farmers: 3, lumberjacks: 0, quarriers: 0, craftPlywood, sheds: 15, universities: 7, banks: 5, mines: 1, foundries: 3, apprentices: true, lumber: 1, food: 100, seed: 2 })),
    { population: 8, farmers: 0, lumberjacks: 0, quarriers: 0, craftPlywood: 2, craftBrick: 2, craftIron: 1, sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 1, ironMining: true, cementPlants: 1, sawmills: 2, foundries: 5, garrisons: 1, libraries: 6, researchGrant: true, carpentry: true, lumber: 100, cementInput: 10, ironInput: 10, food: 0, seed: 20, multiplier: 1.375 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, sheds: 1, universities: 1, garrisons: 1, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, sheds: 1, universities: 1, garrisons: 1, soldiers: 0, trainingProgress: 99.5, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, sheds: 1, universities: 1, garrisons: 1, soldiers: 1, trainingProgress: 99.5, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 2, quarriers: 2, sheds: 2, universities: 1, garrisons: 2, soldiers: 4, food: 0, seed: 1, multiplier: 1.375 },
    { population: 0, farmers: 0, lumberjacks: 0, quarriers: 0, sheds: 1, universities: 1, garrisons: 1, soldiers: 2, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 2, sheds: 8, universities: 5, banks: 2, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 2, food: 100, seed: 2 },
    { population: 8, farmers: 3, lumberjacks: 1, quarriers: 1, professors: 2, sheds: 8, universities: 5, banks: 2, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 2, thesis: true, food: 100, seed: 2 },
    { population: 8, farmers: 0, lumberjacks: 1, quarriers: 1, professors: 2, sheds: 15, universities: 7, banks: 5, mines: 1, cementPlants: 1, foundries: 1, garrisons: 1, libraries: 3, researchGrant: true, food: 0, seed: 1, multiplier: 1.375 },
]) {
    const env = settlement(options);
    const { population, farmers, lumberjacks, quarriers, food, seed } = options;
    env.g.resource.human.amount = population;
    env.g.resource.Food.amount = food;
    if (options.lumber !== undefined) env.g.resource.Lumber.amount = options.lumber;
    if (options.stone !== undefined) env.g.resource.Stone.amount = options.stone;
    if (options.cementInput !== undefined) env.g.resource.Cement.amount = options.cementInput;
    if (options.ironInput !== undefined) env.g.resource.Iron.amount = options.ironInput;
    if (options.ironSlots !== undefined) env.g.city.smelter.Iron = options.ironSlots;
    if (options.soldiers !== undefined) env.g.civic.garrison.workers = options.soldiers;
    if (options.soldiers > 0 || options.trainingProgress !== undefined) env.g.civic.garrison.rate = 0.5;
    if (options.trainingProgress !== undefined) env.g.civic.garrison.progress = options.trainingProgress;
    env.g.seed = seed;
    env.g.civic.unemployed.workers = population;
    env.assign('farmer', farmers);
    env.assign('lumberjack', lumberjacks);
    env.assign('quarry_worker', quarriers);
    env.assign('miner', options.miners || 0);
    env.assign('craft_Plywood', options.craftPlywood || 0);
    env.assign('craft_Brick', options.craftBrick || 0);
    env.assign('craft_Wrought_Iron', options.craftIron || 0);
    if (options.minerTarget) env.g.civic.miner.assigned = options.minerTarget;
    env.assign('cement_worker', options.cementWorkers || 0);
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
    ['miner', 100, 0], ['miner', 1, 2], ['miner', -1, 2], ['miner', -100, 1],
    ['cement_worker', 100, 0], ['cement_worker', 1, 2], ['cement_worker', -1, 2], ['cement_worker', -100, 1]]) {
    const env = settlement(job === 'cement_worker' ? { sheds: 7, universities: 1, banks: 2, cementPlants: 1 } : job === 'miner' ? { sheds: 2, universities: 1, mines: 2 } : job === 'banker' ? { sheds: 2, universities: 2, banks: 2, investing: true } : job === 'professor' ? { sheds: 2, universities: 2 } : {});
    env.g.resource.human.amount = 5;
    env.g.civic.unemployed.workers = 5;
    env.assign(job, initial);
    const input = env.save();
    const before = env.g.civic[job].workers;
    env.assign(job, delta);
    assignments.push({ name: `${job}-${initial}-${delta}`, input, job, delta,
        applied: Math.abs(env.g.civic[job].workers - before), expected: env.view() });
}
const smelterAssignments = [];
for (const [job, delta, initial] of [['craft_Plywood', 100, 0], ['craft_Brick', 1, 1], ['craft_Wrought_Iron', -1, 1], ['craft_Plywood', -100, 2]]) {
    const env = settlement({ sheds: 8, universities: 5, banks: 2, mines: 1, ironMining: true, foundries: 3 });
    env.g.resource.human.amount = 5;
    env.g.civic.unemployed.workers = 5;
    env.assign('craft_Brick', 1);
    env.assign(job, initial);
    const input = env.save();
    const before = env.g.city.foundry[job.substring(6)];
    env.assign(job, delta);
    assignments.push({ name: `${job}-${initial}-${delta}`, input, job, delta,
        applied: Math.abs(env.g.city.foundry[job.substring(6)] - before), expected: env.view() });
}
for (const [id, delta, wood, iron] of [['Wood', 100, 0, 0], ['Wood', -1, 2, 0], ['Wood', -100, 2, 1],
    ['Wood', 1, 2, 2], ['Iron', 100, 2, 0], ['Iron', -100, 2, 2], ['Iron', 1, 1, 1], ['Iron', -1, 2, 1]]) {
    const env = settlement({ sheds: 16, quarries: 12, yards: 5, universities: 8, banks: 8, mines: 2, ironMining: true, smelters: 2 });
    env.g.city.smelter.Wood = wood;
    env.g.city.smelter.Iron = iron;
    const input = env.save();
    const before = env.g.city.smelter[id];
    env.adjustSmelter(id, delta);
    smelterAssignments.push({ id, delta, input, applied: Math.abs(env.g.city.smelter[id] - before), expected: env.view() });
}
const result = {
    provenance: files.map(file => ({ file, sha256: crypto.createHash('sha256').update(sources[file]).digest('hex') })),
    boundary: 'Human/grassland/standard only. Executes original sentience, resource defaults, selected tech/building actions and costs including sheds/universities/banks/mines/smelters/cement plants/sawmills/foundries, investing, iron mining and copper/iron axes/pickaxes/iron saws, job add/sub and finite-job refill, farmerValue, food/growth/knowledge/lumber/stone/miner/income blocks and base/storage/university/bank/farm housing midLoop caps with original bank_vault and storageMultipler. Farm housing adds one citizen capacity per farm; research applies to existing farms at the next midLoop and subsequent farms apply immediately. Reinforced sheds multiply base storage by 2.25, rounding each per-shed amount before multiplying the count; existing sheds upgrade at the next midLoop, subsequent sheds use upgraded capacity immediately. Neutral spatialReasoning rounds values with no prestige modifier. Miner, professor and banker capacity is one per matching building; assigned targets survive deaths. Cement workers have two positions per plant, consume 3 stone/s before quarry production and produce 0.4 cement/s with hunger and host multipliers applied only to output. Sawmills add 200 lumber capacity and 5% lumber production each. Copper, iron and cement start at 100 capacity; previously built sheds apply only at the next midLoop after material unlock, new sheds apply immediately. Upgraded iron-tool setups execute original midLoop during preparation to apply existing sheds before funding the iron costs. Investing grants a fed-only 10% tax bonus per banker, with original per-fastLoop rounding. Wood smelting executes original industry add/sub controls, fuel configuration and complete smelter fastLoop block before lumber production: 3 lumber/s per operable fuel slot, one iron slot refilled per fastLoop, 10% iron multiplier per effective slot; coal/oil/steel remain locked. Fixture setup rejects failed purchases and builds initial sheds then banks before remaining sheds. Foundries execute original craftCost, full craftingRatio, manual craft method, craftsman add/sub and complete fastLoop crafting block for plywood, brick and wrought iron. Wasteful rank 1/0.5 input costs, artisans, apprentices and carpentry bonuses apply; apprentices use the full assigned recipe workforce even when only some workers have inputs, and carpentry adds sawmill bonuses only to plywood; craftsmen share the foundry count, have no refill targets and map to per-recipe host job IDs. Crafted resources use original capacity -1 and are not clamped. Garrisons execute original military research/building costs, armyRating, garrisonSize, weaponTechModifer, Furs block and complete training block at the end of each fastLoop. Soldiers train independently of civilians, contribute food hunting, food upkeep and taxes; training completion affects production starting the next fastLoop. Human diverse training rate is 0.5 percentage points per fastLoop. Furs use pending shed capacity and reinforced rounding. All garrisons remain active; no combat, wounds, deployment or recruitment controls. Libraries execute original research/actions, knowledge production and midLoop caps. Each library adds 125 knowledge capacity and 5% knowledge production; thesis updates professor impact at the next midLoop or immediately when another library is built, and research grants give universities 2% capacity per library with existing universities recomputed at the next midLoop. Fixture saves persist professor impact and pending knowledge capacities. Setup crafts required library materials through the original manual method with independently funded inputs. Neutral weather, empty geology, tax rate 20, no power, alternative fuels, steel, prestige, events, government, default-job switching or other jobs. Host multiplier is supplied explicitly, Math.random uses persisted seededRandom. Host normalizes workforce at the atomic tick boundary, releasing unemployed then jobs in reverse order after deaths; original delayed labor-anomaly repair is outside this fixture.',
    initialization, cases, production, assignments, smelterAssignments,
};
const output = JSON.stringify(result, null, 2) + '\n';
const target = path.join(root, 'java/core/src/test/resources/upstream-civilization.json');
if (process.argv.includes('--check')) {
    if (read('java/core/src/test/resources/upstream-civilization.json') !== output) throw new Error('Civilization fixtures changed; regenerate and review.');
} else fs.writeFileSync(target, output);
process.stdout.write(`Verified ${initialization.length} sentience, ${cases.length} settlement, ${production.length} production, ${assignments.length} job and ${smelterAssignments.length} smelter scenarios.\n`);
