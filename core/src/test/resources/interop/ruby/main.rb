require 'tranforcpp'

me = Tranfor::PROGRAM_ID
Tranfor.handle('echo') { |value = ''| "#{me}:#{value}" }
Tranfor.handle('bounce') { |target, value| Tranfor.call(target, 'echo', value) }
Tranfor.on('Ping') { |source, target| Tranfor.broadcast("#{me}->#{Tranfor.call(target, 'echo', me)}") if source == me }
Tranfor.on('Nested') { |source, via| Tranfor.broadcast("nested #{me}=#{Tranfor.call(via, 'bounce', me, me)}") if source == me }
Tranfor.on('Missing') do |source|
  next unless source == me

  begin
    Tranfor.call('nobody', 'echo')
  rescue Tranfor::CallError => e
    Tranfor.broadcast("missing #{me}:#{e.message}")
  end
end
Tranfor.on('Announce') { |source| Tranfor.publish('greet', 'hi') if source == me }
Tranfor.subscribe('greet') { |sender, text| Tranfor.broadcast("greet #{me} from #{sender}: #{text}") }
Tranfor.run
