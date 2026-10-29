// Paste into the DevTools console on https://www.youtube.com (any page).
// Captures InnerTube responses for known AI-labeled videos with the WEB and TV
// clients and downloads them as ai-label-capture.json.gz.
// Requests are sent without cookies, so no account data ends up in the file.
(async () => {
  const ids = ['tJllhkPqixM', 'v6So-4uvruU', 'RBOjdAom7DU', 'knFceIUXCXU', '2fT_Sacey8o', '22GHKCclGfI'];
  const clients = {
    WEB: { clientName: 'WEB', clientVersion: '2.20260907.06.00' },
    TV: {
      clientName: 'TVHTML5', clientVersion: '7.20260901.15.00',
      userAgent: 'Mozilla/5.0 (ChromiumStylePlatform) Cobalt/25.lts.30.1034943-gold (unlike Gecko), Unknown_TV_Unknown_0/Unknown (Unknown, Unknown)',
    },
  };
  const call = async (endpoint, client, extra) => {
    const body = { context: { client: { ...client, hl: 'en', gl: 'US' } }, ...extra };
    const res = await fetch(`/youtubei/v1/${endpoint}?prettyPrint=false`, {
      method: 'POST', credentials: 'omit',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    });
    return { status: res.status, json: await res.json().catch(() => null) };
  };
  const out = {};
  for (const id of ids) {
    out[id] = {};
    for (const [name, client] of Object.entries(clients)) {
      out[id][`next_${name}`] = await call('next', client, { videoId: id });
      out[id][`player_${name}`] = await call('player', client, { videoId: id });
      out[id][`search_${name}`] = await call('search', client, { query: id });
      console.log('captured', id, name);
    }
  }
  out.reel_WEB = await call('reel/reel_item_watch', clients.WEB, { playerRequest: { videoId: '2fT_Sacey8o' }, params: 'CAUwAg%3D%3D' });
  const text = JSON.stringify(out);
  console.log('hits for "synthetic":', (text.match(/synthetic/gi) || []).length,
              '| "howThisWasMade":', (text.match(/howThisWasMade/g) || []).length);
  const gz = await new Response(new Blob([text]).stream().pipeThrough(new CompressionStream('gzip'))).blob();
  const a = Object.assign(document.createElement('a'), { href: URL.createObjectURL(gz), download: 'ai-label-capture.json.gz' });
  a.click();
})();
