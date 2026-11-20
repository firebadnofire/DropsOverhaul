// Real protocol client; dependency is installed in build/test-client, never shipped.
const path = require('node:path')
const dependencies = process.env.DO_CLIENT_DEPS || path.resolve(__dirname, '../build/test-client/node_modules')
const mineflayer = require(path.join(dependencies, 'mineflayer'))
const { Vec3 } = require(path.join(dependencies, 'vec3'))
const bot = mineflayer.createBot({host: '127.0.0.1', port: Number(process.env.MC_TEST_PORT || 25575),
  username: 'DOTester', auth: 'offline', version: process.env.MC_TEST_VERSION || '1.21.11'})
require('./protocol-errors.cjs')(bot, dependencies, process.env.MC_TEST_VERSION || '1.21.11')
const delay = ms => new Promise(resolve => setTimeout(resolve, ms))
function message(prefix) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {bot.removeListener('messagestr', listener); reject(new Error('Timeout: ' + prefix))}, 20000)
    function listener(text) {
      if (text.includes('DO_TEST_FAIL')) {clearTimeout(timer); bot.removeListener('messagestr', listener); reject(new Error(text))}
      else if (text.includes(prefix)) {clearTimeout(timer); bot.removeListener('messagestr', listener); resolve(text)}
    }
    bot.on('messagestr', listener)
  })
}
bot.on('error', error => {console.error(error); process.exit(1)})
bot.on('kicked', reason => {console.error('Kicked:', reason); process.exit(1)})
bot.once('spawn', async () => {
  try {
    // prismarine-item currently returns a component object instead of an array
    // for 1.21.11 enchants. Only client timing needs a workaround: server loot
    // and enchantments remain untouched. A conservative delay avoids early digs.
    const nativeDigTime = bot.digTime
    bot.digTime = block => {
      try { return nativeDigTime(block) } catch (error) {
        if (error.message !== 'enchantments is not iterable') throw error
        console.log('Client timing workaround: enchanted tool, 3000ms')
        return 3000
      }
    }
    for (const name of ['commands', 'selected', 'metadata', 'unselected', 'partial', 'full-keep', 'full-delete', 'silk', 'fortune', 'custom', 'creative', 'cancelled']) {
      const ready = message('DO_TEST_READY ' + name)
      bot.chat('/dofixture prepare ' + name)
      await ready
      await delay(700)
      const block = bot.blockAt(new Vec3(0, 100, 2))
      await bot.lookAt(block.position.offset(0.5, 0.5, 0.5))
      if (name === 'cancelled') {bot.dig(block).catch(() => {}); await delay(1500); bot.stopDigging()}
      else await bot.dig(block)
      await delay(500)
      const checked = message('DO_TEST_PASS ' + name)
      bot.chat('/dofixture verify ' + name)
      console.log(await checked)
    }
    console.log('DO_GAMEPLAY_PASS')
    bot.quit()
    setTimeout(() => process.exit(0), 500)
  } catch (error) {console.error(error); bot.quit(); process.exit(1)}
})
setTimeout(() => {console.error('Client deadline exceeded'); process.exit(1)}, 180000).unref()
