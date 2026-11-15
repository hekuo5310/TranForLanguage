<?php
$me = TranForLanguage::programId();
TranForLanguage::handle('echo', fn($value = '') => "$me:$value");
TranForLanguage::handle('bounce', fn($target, $value) => TranForLanguage::call($target, 'echo', $value));
TranForLanguage::on('Ping', function ($source, $target) use ($me) {
    if ($source === $me) TranForLanguage::broadcast("{$me}->" . TranForLanguage::call($target, 'echo', $me));
});
TranForLanguage::on('Nested', function ($source, $via) use ($me) {
    if ($source === $me) TranForLanguage::broadcast("nested $me=" . TranForLanguage::call($via, 'bounce', $me, $me));
});
TranForLanguage::on('Missing', function ($source) use ($me) {
    if ($source !== $me) return;
    try { TranForLanguage::call('nobody', 'echo'); } catch (TranForLanguageCallError $e) { TranForLanguage::broadcast("missing $me:" . $e->getMessage()); }
});
TranForLanguage::on('Server', function ($source) use ($me) {
    if ($source === $me) TranForLanguage::broadcast("server $me=" . TranForLanguage::server('ping', $me));
});
TranForLanguage::on('Announce', function ($source) use ($me) { if ($source === $me) TranForLanguage::publish('greet', 'hi'); });
TranForLanguage::subscribe('greet', function ($sender, $text) use ($me) { TranForLanguage::broadcast("greet $me from $sender: $text"); });
TranForLanguage::run();
