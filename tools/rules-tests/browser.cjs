// Keep apt/needrestart non-interactive even when sudo drops the caller's environment.
function browserInstallCommands(cli,{platform=process.platform,uid=process.getuid?.(),node=process.execPath}={}) {
 const commands=[];
 if(platform==='linux') {
  const install=['env','DEBIAN_FRONTEND=noninteractive','NEEDRESTART_MODE=l',node,cli,'install-deps','chromium'];
  commands.push({stage:'browser-os-dependencies',exe:uid===0?install[0]:'sudo',args:uid===0?install.slice(1):['-n',...install],log:'browser-dependencies.log'});
 }
 commands.push({stage:'pinned-browser-download',exe:node,args:[cli,'install','chromium'],log:'browser-install.log'});
 return commands;
}
module.exports={browserInstallCommands};
