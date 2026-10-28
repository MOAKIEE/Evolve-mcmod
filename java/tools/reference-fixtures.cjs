// Executes selected ORIGINAL source functions in a development-only Node VM.
// The committed fixtures are consumed by JUnit; neither Java nor Minecraft executes this script.
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const crypto = require('node:crypto');
const root = path.resolve(__dirname, '../..');
const read = name => fs.readFileSync(path.join(root, name), 'utf8').replace(/\r\n/g, '\n');
const actionsSource = read('src/actions.js');
const mainSource = read('src/main.js');
const varsSource = read('src/vars.js');
function functionText(source, name) {
    const start = source.indexOf(`function ${name}(`);
    const end = source.indexOf('\n}', start) + 2;
    if (start < 0 || end < 2) throw new Error(`Missing function ${name}`);
    return source.slice(start, end);
}
const evolutionSource = actionsSource.slice(actionsSource.indexOf('    evolution: {'), actionsSource.indexOf('    city: {'));
const fastStart = mainSource.indexOf('        // Gain RNA & DNA');
const fastEnd = mainSource.indexOf('    else {\n        // Rest of game', fastStart);
const fastSource = mainSource.slice(fastStart, fastEnd).replace(/\s*\}\s*$/, '');
const midStart = mainSource.indexOf('        let base = 100;', mainSource.indexOf('function midLoop()'));
const midSource = mainSource.slice(midStart, mainSource.indexOf('        Object.keys(actions.evolution)', midStart));
const species = {};
for (const line of read('java/core/src/main/resources/evolve/species.properties').split('\n')) {
    const match = line.match(/^(\w+)\.genus=(\w+)$/);
    if (match) species[match[1]] = { type: match[2] };
}
const raceList = read('java/core/src/main/resources/evolve/species.properties').match(/^order=(.*)$/m)[1].split(',');
const genusOrder = read('java/core/src/main/resources/evolve/genus.properties').match(/^order=(.*)$/m)[1].split(',');
const fresh = {
    biome: 'grassland', universe: 'standard', seeded: false, rapidMutation: false,
    massExtinction: 0, novice: 0, master: 0, grandmaster: 0, unbound: 0, obsolete: 0,
    nightmare: false, extinctSpecies: [], productionMultiplier: 1,
};
function environment(test) {
    const c = test.context;
    const g = {
        resource: { RNA: { amount: test.rna, max: test.rnaCap, display: true },
                    DNA: { amount: test.dna, max: test.dnaCap, display: test.dnaVisible } },
        evolution: Object.fromEntries(Object.entries(test.structures).map(([k, count]) => [k, { count }])),
        tech: { ...test.technologies }, race: { species: 'protoplasm', universe: c.universe, seeded: c.seeded, chose: true },
        city: { biome: c.biome }, genes: {}, blood: {}, seed: 2,
        stats: { feat: {}, achieve: {} },
    };
    g.evolution.final = test.progress;
    if (test.dnaVisible) g.evolution.dna = 1;
    if (c.rapidMutation) g.race.rapid_mutation = 1;
    if (c.grandmaster >= 5) g.evolution.gselect = true;
    if (c.massExtinction) g.stats.achieve.mass_extinction = { l: c.massExtinction };
    if (c.novice) { g.stats.feat.novice = c.novice; g.stats.achieve.apocalypse = { l: c.novice }; }
    if (c.obsolete) g.stats.achieve.obsolete = { l: c.obsolete };
    if (c.nightmare) g.stats.achieve.nightmare = { mg: true };
    if (c.unbound) g.blood.unbound = c.unbound;
    const sandbox = {
        global: g, loc: key => key, $: object => [object], drawEvolution: () => {},
        format_emblem: () => '', sentience: () => { g.terminal = g.race.species; },
        genus_def: Object.fromEntries(genusOrder.map(type => [type, {}])), races: species, raceList,
        modRes: (id, value, notrack) => { g.resource[id].amount = Math.max(0, Math.min(
            notrack ? g.resource[id].max : g.resource[id].buffer, g.resource[id].amount + value)); },
        payCosts: definition => {
            const costs = Object.entries(definition.cost || {}).map(([id, cost]) => [id, cost()]);
            if (costs.some(([id, cost]) => g.resource[id].amount < cost)) return false;
            for (const [id, cost] of costs) g.resource[id].amount -= cost;
            return true;
        },
    };
    const context = vm.createContext(sandbox, { codeGeneration: { strings: false, wasm: false } });
    vm.runInContext(`${functionText(actionsSource, 'genus_condition')}\n${functionText(actionsSource, 'evolveCosts')}\n` +
        `${functionText(varsSource, 'seededRandom')}\nvar actions = {${evolutionSource}};` +
        `\nfunction fast(){ const global_multiplier = ${c.productionMultiplier}; const time_multiplier = .25; ${fastSource} }` +
        `\nfunction mid(){ ${midSource} }`, context, { timeout: 1000 });
    const visible = ([, definition]) => Object.entries(definition.reqs || {}).every(([id, n]) => (g.tech[id] || 0) >= n) &&
        (!definition.condition || definition.condition());
    return { g, context, actions: sandbox.actions.evolution, visible };
}
function state(g) {
    return {
        rna: g.resource.RNA.amount, dna: g.resource.DNA.amount,
        rnaCap: g.resource.RNA.max, dnaCap: g.resource.DNA.max, dnaVisible: g.resource.DNA.display,
        structures: Object.fromEntries(Object.entries(g.evolution).filter(([, v]) => typeof v === 'object').map(([id, v]) => [id, v.count])),
        technologies: g.tech, progress: g.evolution.final || 0, species: g.terminal || 'protoplasm',
    };
}
const allStructures = { membrane: 4, organelles: 3, nucleus: 2, eukaryotic_cell: 4, mitochondria: 2 };
const scenarios = [];
function scenario(name, patch = {}, context = {}) {
    scenarios.push({ name, context: { ...fresh, ...context }, rna: 400, dna: 400, rnaCap: 500, dnaCap: 500,
        dnaVisible: true, structures: allStructures, technologies: {}, progress: 0, ...patch });
}
scenario('fresh', { rna: 0, dna: 0, rnaCap: 100, dnaCap: 100, dnaVisible: false, structures: {} });
scenario('unlock-dna', { rna: 2, dna: 0, dnaVisible: false, structures: {} });
scenario('unlock-membrane', { rna: 10, dna: 0, structures: {} });
scenario('unlock-organelles', { rna: 0, dna: 4, structures: {} });
scenario('nucleus-starved', { rna: 1.99 });
scenario('nucleus-partial', { rna: 3.5 });
scenario('full-dna', { dna: 500 });
scenario('rapid-novice', {}, { rapidMutation: true, novice: 3, productionMultiplier: 1.375 });
scenario('mass-extinction-unlock', { rna: 2, dna: 0, dnaVisible: false, structures: {} }, { massExtinction: 5 });
for (let stage = 1; stage <= 7; stage++) {
    for (const type of ['animal', 'plant', 'fungi']) {
        const technologies = { evo: stage, [`evo_${type}`]: 1 };
        if (stage >= 5 && type === 'animal') for (const t of ['insectoid', 'mammals', 'eggshell', 'eldritch', 'aquatic', 'fey', 'sand', 'heat', 'polar']) technologies[`evo_${t}`] = 1;
        if (stage >= 6) for (const t of ['humanoid', 'giant', 'small', 'animalism', 'demonic', 'angelic']) technologies[`evo_${t}`] = 1;
        scenario(`${type}-stage-${stage}`, { technologies });
    }
}
scenario('avian-stage', { technologies: { evo: 6, evo_eggshell: 2 } });
scenario('diet-stage', { progress: 95, technologies: { evo: 7, evo_animalism: 2 } });
scenario('all-biomes-unbound', { technologies: { evo: 5, ...Object.fromEntries(['aquatic', 'fey', 'sand', 'heat', 'polar', 'eldritch'].map(t => [`evo_${t}`, 1])) } }, { unbound: 3, nightmare: true });
scenario('grandmaster', { progress: 80, technologies: { evo: 6, evo_eggshell: 2, evo_animalism: 2,
    ...Object.fromEntries(['humanoid', 'giant', 'small', 'demonic', 'angelic', 'insectoid', 'eldritch', 'sand', 'polar', 'heat', 'fey', 'aquatic'].map(t => [`evo_${t}`, 1])) } }, { grandmaster: 5, unbound: 3, obsolete: 5, nightmare: true });
scenario('random-sentience', { progress: 100, technologies: { evo: 7, evo_humanoid: 2 } });
const cases = scenarios.map(test => {
    const env = environment(test);
    const available = Object.entries(env.actions).filter(env.visible).map(([id, rule]) => ({
        id: `evolution-${id}`, costs: Object.fromEntries(Object.entries(rule.cost || {}).map(([id, cost]) => [id, cost()]).filter(([, n]) => n > 0)),
    }));
    const actionResults = available.map(({ id }) => {
        const next = environment(test);
        const rule = next.actions[id.slice('evolution-'.length)];
        const success = rule.action.call(rule, {});
        if (success && rule.grant) next.g.tech[rule.grant[0]] = Math.max(next.g.tech[rule.grant[0]] || 0, rule.grant[1]);
        return { id, expected: state(next.g) };
    });
    const fastFrames = [];
    for (let step = 1; step <= 20; step++) {
        for (const resource of Object.values(env.g.resource)) resource.buffer = resource.max + resource.amount;
        vm.runInContext('fast()', env.context, { timeout: 1000 });
        for (const resource of Object.values(env.g.resource)) resource.amount = Math.min(resource.max, resource.amount);
        if (step % 4 === 0) vm.runInContext('mid()', env.context, { timeout: 1000 });
        if ([1, 4, 20].includes(step)) fastFrames.push({ ticks: step * 5, expected: JSON.parse(JSON.stringify(state(env.g))) });
    }
    return { ...test, available, actionResults, fastFrames };
});
const result = {
    provenance: ['src/actions.js', 'src/main.js', 'src/vars.js'].map(file => ({ file, sha256: crypto.createHash('sha256').update(read(file)).digest('hex') })),
    boundary: 'Original evolution action/fast/capacity functions; host multiplier and prestige inputs supplied explicitly. Full sentience initialization is outside this fixture.',
    cases,
};
const target = path.join(root, 'java/core/src/test/resources/upstream-evolution.json');
const output = JSON.stringify(result, null, 2) + '\n';
if (process.argv.includes('--check')) {
    if (fs.readFileSync(target, 'utf8').replace(/\r\n/g, '\n') !== output) throw new Error('Upstream fixtures changed; regenerate and review the differences.');
} else {
    fs.mkdirSync(path.dirname(target), { recursive: true });
    fs.writeFileSync(target, output);
}
process.stdout.write(`Verified ${cases.length} original-source scenarios.\n`);
