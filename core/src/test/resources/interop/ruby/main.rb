require 'tranforlanguage'

me = TranForLanguage::PROGRAM_ID
TranForLanguage.handle('echo') { |value = ''| "#{me}:#{value}" }
TranForLanguage.handle('bounce') { |target, value| TranForLanguage.call(target, 'echo', value) }
TranForLanguage.on('Ping') { |source, target| TranForLanguage.broadcast("#{me}->#{TranForLanguage.call(target, 'echo', me)}") if source == me }
TranForLanguage.on('Nested') { |source, via| TranForLanguage.broadcast("nested #{me}=#{TranForLanguage.call(via, 'bounce', me, me)}") if source == me }
TranForLanguage.on('Missing') do |source|
  next unless source == me

  begin
    TranForLanguage.call('nobody', 'echo')
  rescue TranForLanguage::CallError => e
    TranForLanguage.broadcast("missing #{me}:#{e.message}")
  end
end
TranForLanguage.on('Server') { |source| TranForLanguage.broadcast("server #{me}=#{TranForLanguage.server('ping', me)}") if source == me }
TranForLanguage.on('Announce') { |source| TranForLanguage.publish('greet', 'hi') if source == me }
TranForLanguage.subscribe('greet') { |sender, text| TranForLanguage.broadcast("greet #{me} from #{sender}: #{text}") }
TranForLanguage.run
