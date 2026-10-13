package org.gradle.wrapper;
import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;
/** Project-specific bootstrap, not the upstream Gradle wrapper. */
public final class GradleWrapperMain {
 static final String VERSION="8.13";
 static final String URL="https://services.gradle.org/distributions/gradle-"+VERSION+"-bin.zip";
 static void download(String url,Path out)throws Exception{
  URLConnection c=new URI(url).toURL().openConnection();c.setConnectTimeout(30000);c.setReadTimeout(120000);
  try(InputStream in=c.getInputStream()){Files.copy(in,out,StandardCopyOption.REPLACE_EXISTING);}
 }
 static String digest(Path file)throws Exception{
  MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] b=new byte[65536];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}
  return HexFormat.of().formatHex(md.digest());
 }
 public static void main(String[] args)throws Exception{
  if(args.length==1&&args[0].equals("--bootstrap-info")){System.out.println("MobileOsint bootstrap; Gradle "+VERSION+"; SHA-256 verified distribution; Java "+System.getProperty("java.version"));return;}
  Path cache=Paths.get(System.getProperty("user.home"),".gradle","mobile-osint-bootstrap");Files.createDirectories(cache);
  Path home=cache.resolve("gradle-"+VERSION);
  boolean win=System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");Path exe=home.resolve("bin").resolve(win?"gradle.bat":"gradle");
  try(FileChannel channel=FileChannel.open(cache.resolve("bootstrap.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);FileLock lock=channel.lock()){
   if(!Files.exists(exe)){
    System.out.println("Downloading pinned Gradle distribution and verifying SHA-256...");
    Path archive=cache.resolve("gradle.zip"),checksum=cache.resolve("gradle.sha256");download(URL,archive);download(URL+".sha256",checksum);
    String expected=Files.readString(checksum).trim().split("\\s+")[0];if(!digest(archive).equalsIgnoreCase(expected))throw new SecurityException("Gradle checksum mismatch");
    try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))){ZipEntry entry;while((entry=zip.getNextEntry())!=null){Path target=cache.resolve(entry.getName()).normalize();if(!target.startsWith(cache))throw new SecurityException("Unsafe archive path");if(entry.isDirectory())Files.createDirectories(target);else{Files.createDirectories(target.getParent());Files.copy(zip,target,StandardCopyOption.REPLACE_EXISTING);}}}
    exe.toFile().setExecutable(true);Files.deleteIfExists(archive);Files.deleteIfExists(checksum);
   }
  }
  List<String> command=new ArrayList<>();if(win){command.add("cmd.exe");command.add("/c");}command.add(exe.toString());command.addAll(Arrays.asList(args));
  System.exit(new ProcessBuilder(command).inheritIO().start().waitFor());
 }
}
