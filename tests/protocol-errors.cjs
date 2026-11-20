// ProtoDef logs and discards some partial packets without emitting a client error.
// Report the one known, unrelated 1.21 recipe decoder limitation explicitly;
// errors in any other packet must fail gameplay validation.
module.exports = function trackProtocolErrors(bot, dependencies, version) {
  const path = require('node:path')
  const protocol = require(path.join(dependencies, 'minecraft-data'))(version).protocol
  bot._client.on('state', state => {
    const parser = bot._client.deserializer
    const read = parser.parsePacketBuffer.bind(parser)
    parser.parsePacketBuffer = buffer => {
      try { return read(buffer) } catch (error) {
        let id = 0
        for (let i = 0; i < Math.min(buffer.length, 5); i++) {
          id |= (buffer[i] & 0x7f) << (7 * i)
          if (!(buffer[i] & 0x80)) break
        }
        const names = protocol[state]?.toClient?.types?.packet?.[1]?.[0]?.type?.[1]?.mappings
        const packet = names?.['0x' + id.toString(16).padStart(2, '0')] || 'unknown-' + id
        if (version === '1.21' && packet === 'declare_recipes' && error.partialReadError && error.stack.includes('ArmorTrimMaterial')) {
          console.warn('DO_CLIENT_WARNING ' + JSON.stringify({state, packet, error: error.message,
            scope: 'Mineflayer recipe decoding; crafting is not tested. Drop and inventory assertions use separate packets.'}))
        } else {
          bot.emit('error', new Error('Protocol decoder failed for ' + state + '.' + packet, {cause: error}))
        }
        throw error
      }
    }
  })
}
