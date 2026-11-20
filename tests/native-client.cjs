// Test modern native servers using console-prepared scenes and actual digging.
const path = require('node:path')
const dependencies = process.env.DO_CLIENT_DEPS || path.resolve(__dirname, '../build/test-client/node_modules')
const mineflayer = require(path.join(dependencies, 'mineflayer'))
const { Vec3 } = require(path.join(dependencies, 'vec3'))
const assert = require('node:assert/strict')
const bot = mineflayer.createBot({host: '127.0.0.1', port: 25575, username: 'DOTester', auth: 'offline', version: process.env.MC_TEST_VERSION})
require('./protocol-errors.cjs')(bot, dependencies, process.env.MC_TEST_VERSION)
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
function waitMessage(prefix) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {bot.removeListener('messagestr', listener); reject(new Error('Timeout: ' + prefix))}, 20000)
    function listener(text) {
      if (text.includes(prefix)) {clearTimeout(timer); bot.removeListener('messagestr', listener); resolve(text)}
    }
    bot.on('messagestr', listener)
  })
}
async function admin(action, block = '') {
  const done = waitMessage(action === 'reload' ? 'Successfully reloaded configuration.' : 'Successfully ' + (action === 'addblock' ? 'added ' + block + ' to' : 'removed ' + block + ' from') + ' the block list.')
  bot.chat('/dropsoverhaul ' + action + (block ? ' ' + block : ''))
  await done
}
async function listedGold() {
  const messages = []
  const collect = text => messages.push(text)
  bot.on('messagestr', collect)
  try { bot.chat('/dropsoverhaul list'); await admin('reload') }
  finally {bot.removeListener('messagestr', collect)}
  return messages.filter(text => /^\s*-?\s*GOLD_BLOCK\s*$/.test(text)).length
}
async function permissions(action) {
  const ready = waitMessage('DO_PERMISSION_STATUS ' + action)
  console.log(JSON.stringify({permissions: action}))
  await ready
}
async function waitForArena() {
  const expected = new Vec3(0.5, 100, 0.5)
  for (let attempt = 0; attempt < 100; attempt++) {
    const floor = bot.blockAt(new Vec3(0, 99, 0))
    if (bot.entity.position.distanceTo(expected) < 0.1 && floor?.name === 'bedrock') {
      await bot.waitForChunksToLoad()
      await delay(300)
      if (bot.entity.position.distanceTo(expected) < 0.1) return
    }
    await delay(100)
  }
  throw new Error('Arena teleport/chunks did not settle: ' + bot.entity.position)
}
async function testCommands() {
  for (const command of ['/dropsoverhaul', '/do']) {
    const help = waitMessage('Drops Overhaul Commands')
    bot.chat(command)
    await help
  }
  const aliasList = waitMessage('Drops Overhaul-affected blocks list')
  bot.chat('/do list')
  await aliasList
  await admin('addblock', 'GOLD_BLOCK')
  await admin('addblock', 'GOLD_BLOCK')
  assert.equal(await listedGold(), 1, 'Idempotent persisted block addition')
  await admin('removeblock', 'GOLD_BLOCK')
  await permissions('deop')
  const denied = new Promise((resolve, reject) => {
    const timer = setTimeout(() => {bot.removeListener('messagestr', listener); reject(new Error('No native permission denial'))}, 10000)
    function listener(text) {
      console.log('Permission response:', text)
      if (/Unknown or incomplete command|Incorrect argument for command|permission/i.test(text)) {clearTimeout(timer); bot.removeListener('messagestr', listener); resolve()}
    }
    bot.on('messagestr', listener)
  })
  bot.chat('/dropsoverhaul addblock GOLD_BLOCK')
  await denied
  await permissions('op')
  assert.equal(await listedGold(), 0, 'Unauthorized block addition was rejected')
  const cleanupLog = text => console.log('Cleanup response:', text)
  bot.on('messagestr', cleanupLog)
  const ready = waitMessage('DO_CLEANUP_READY ')
  bot.chat('/dofixtureitems')
  const ids = (await ready).match(/DO_CLEANUP_READY (\d+) (\d+)/).slice(1).map(Number)
  await delay(300)
  let removed = waitMessage('Successfully removed 1 items.')
  bot.chat('/killdrops 2')
  await removed
  await delay(300)
  assert(!bot.entities[ids[0]] && bot.entities[ids[1]], 'Location-centered cleanup radius')
  removed = waitMessage('Successfully removed 1 items.')
  bot.chat('/kd')
  await removed
  await delay(300)
  bot.removeListener('messagestr', cleanupLog)
}
bot.on('error', error => {console.error(error); process.exit(1)})
bot.on('kicked', reason => {console.error('Kicked:', reason); process.exit(1)})
bot.once('spawn', async () => {
  try {
    await bot.waitForChunksToLoad()
    const nativeDigTime = bot.digTime
    bot.digTime = block => {
      try { return nativeDigTime(block) } catch (error) {
        if (error.message !== 'enchantments is not iterable') throw error
        console.log('Client timing workaround: enchanted tool, 3000ms')
        return 3000
      }
    }
    const listed = waitMessage('Drops Overhaul-affected blocks list')
    bot.chat('/dropsoverhaul list')
    await listed
    let nativeAmount = 0
    bot.on('messagestr', text => {const match = text.match(/DO_NATIVE_LOOT (\d+)/); if (match) nativeAmount += Number(match[1])})
    for (const name of ['commands', 'selected', 'unselected', 'partial', 'full-keep', 'full-delete', 'silk', 'fortune', 'custom', 'creative', 'cancelled', 'gamerule']) {
      nativeAmount = 0
      const ready = waitMessage('DO_TEST_READY ' + name)
      console.log(JSON.stringify({prepare: name}))
      await ready
      await waitForArena()
      if (name === 'commands') await testCommands()
      await delay(700)
      bot.setQuickBarSlot(0)
      const block = bot.blockAt(new Vec3(0, 100, 3))
      assert(block && block.name !== 'air', 'Scene block is present')
      await bot.lookAt(block.position.offset(0.5, 0.5, 0.5))
      if (name === 'cancelled') {bot.dig(block).catch(() => {}); await delay(1500); bot.stopDigging()}
      else await bot.dig(block)
      await delay(500)
      const item = name === 'custom' ? 'emerald' : name === 'silk' ? 'stone' : name === 'fortune' ? 'diamond' : name === 'unselected' ? 'gold_block' : 'cobblestone'
      console.log('Observed', name, 'blockAfter=' + bot.blockAt(new Vec3(0, 100, 3)).name, 'items=' + JSON.stringify(bot.inventory.items().map(s => [s.name,s.count])), 'entities=' + JSON.stringify(Object.values(bot.entities).map(e => e.name)))
      const inventory = bot.inventory.items().filter(stack => stack.name === item).reduce((sum, stack) => sum + stack.count, 0)
      // Count the block and player overflow area, excluding unrelated cave loot.
      const ground = Object.values(bot.entities).filter(entity => entity.name === 'item'
        && entity.position.distanceTo(new Vec3(0, 100, 1.5)) < 8).length
      console.log('Ground item details', JSON.stringify(Object.values(bot.entities)
        .filter(entity => entity.name === 'item')
        .map(entity => ({id: entity.id, position: entity.position, metadata: entity.metadata}))))
      if (name === 'unselected' || name === 'full-keep') {assert.equal(inventory, 0); assert.equal(ground, 1)}
      else if (['full-delete', 'creative', 'cancelled', 'gamerule'].includes(name)) {assert.equal(inventory, 0); assert.equal(ground, 0)}
      else if (name === 'fortune') {assert(inventory >= 1 && inventory <= 4); assert.equal(inventory, nativeAmount); assert.equal(ground, 0)}
      else {assert.equal(inventory, name === 'partial' ? 64 : name === 'custom' ? 3 : 1); assert.equal(ground, 0)}
      assert.equal(bot.blockAt(new Vec3(0, 100, 3)).name, name === 'cancelled' ? 'stone' : 'air')
      assert.equal(bot.heldItem.durabilityUsed, name === 'creative' || name === 'cancelled' ? 0 : 1, 'Native durability')
      console.log(`DO_TEST_PASS ${name} inventory=${inventory} groundEntities=${ground}`)
    }
    console.log('DO_GAMEPLAY_PASS')
    bot.quit()
    setTimeout(() => process.exit(0), 500)
  } catch (error) {console.error(error); bot.quit(); process.exit(1)}
})
setTimeout(() => {console.error('Client deadline exceeded'); process.exit(1)}, 180000).unref()

