// IPrivilegedService.aidl
package com.gree1d.reappzuku;

interface IPrivilegedService {
    int execute(String command);

    String executeForOutput(String command);
}
