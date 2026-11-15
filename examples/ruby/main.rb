require 'tranforcpp'

Tranfor.on('PlayerJoin') do |player|
  Tranfor.send_message(player, 'Welcome from Ruby!')
end

Tranfor.run
