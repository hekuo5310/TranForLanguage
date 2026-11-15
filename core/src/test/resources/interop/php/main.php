<?php
$me = Tranfor::programId();
Tranfor::handle('echo', fn($value = '') => "$me:$value");
Tranfor::handle('bounce', fn($target, $value) => Tranfor::call($target, 'echo', $value));
Tranfor::on('Ping', function ($source, $target) use ($me) {
    if ($source === $me) Tranfor::broadcast("{$me}->" . Tranfor::call($target, 'echo', $me));
});
Tranfor::on('Nested', function ($source, $via) use ($me) {
    if ($source === $me) Tranfor::broadcast("nested $me=" . Tranfor::call($via, 'bounce', $me, $me));
});
Tranfor::on('Missing', function ($source) use ($me) {
    if ($source !== $me) return;
    try { Tranfor::call('nobody', 'echo'); } catch (TranforCallError $e) { Tranfor::broadcast("missing $me:" . $e->getMessage()); }
});
Tranfor::on('Announce', function ($source) use ($me) { if ($source === $me) Tranfor::publish('greet', 'hi'); });
Tranfor::subscribe('greet', function ($sender, $text) use ($me) { Tranfor::broadcast("greet $me from $sender: $text"); });
Tranfor::run();
