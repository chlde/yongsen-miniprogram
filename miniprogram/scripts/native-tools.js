const automator=require('miniprogram-automator');
async function connect(){return automator.connect({wsEndpoint:process.env.NATIVE_WS||'ws://127.0.0.1:9420'});}
async function call(mp,action,data={},requestId){
 const result=await mp.evaluate((a,d,k)=>wx.cloud.callFunction({name:'ys-training',data:{action:a,data:d,requestId:k}}).then(r=>r.result),action,data,requestId||`${action}-${Date.now()}-${Math.random()}`);
 if(result.code!=='OK')throw Object.assign(Error(result.message),result);
 return result.data;
}
async function wait(label,fn,timeout=45000){const end=Date.now()+timeout;while(Date.now()<end){const value=await fn();if(value)return value;await new Promise(r=>setTimeout(r,700));}throw Error(`Timed out: ${label}`);}
module.exports={connect,call,wait};
