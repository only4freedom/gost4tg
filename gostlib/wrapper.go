package gostlib

import (
	"flag"
	"os"
)

// Start 是给安卓调用的接口
// configPath 是 gost.json 在手机里的绝对路径
func Start(configPath string) {
	// 1. 重置命令行参数 (防止重启服务时报错)
	flag.CommandLine = flag.NewFlagSet(os.Args[0], flag.ExitOnError)
    
    // 2. 模拟命令行输入：gost -C /data/.../gost.json
    // V2 版本使用 -C 参数指定配置文件
    os.Args = []string{"gost", "-C", configPath}

    // 3. 调用原版 V2 的启动逻辑
    internalMain()
}
