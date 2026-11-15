require 'tranforlanguage'

TranForLanguage.on('PlayerJoin') do |player|
  TranForLanguage.send_message(player, 'Welcome from Ruby!')
end

TranForLanguage.run
